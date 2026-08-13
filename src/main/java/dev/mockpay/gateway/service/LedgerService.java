package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.LedgerEntry;
import dev.mockpay.gateway.repo.LedgerEntryRepository;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The books. Every movement of value in the system passes through here.
 *
 * <p>The reason a payment system needs a ledger separate from its payment table is that the payment
 * table answers "what happened to this order" while the ledger answers "where is all the money".
 * Those diverge fast. A single capture simultaneously creates a receivable from the scheme, a
 * payable to the merchant, and fee revenue for the gateway — three balances that must move together
 * and that no status column on a payment row can represent.
 *
 * <p>The invariant enforced on write is that every journal sums to zero. It sounds pedantic until
 * the first time a bug would otherwise have credited a merchant twice: with this check the write
 * fails loudly at the moment of the mistake, rather than surfacing weeks later as an unexplainable
 * gap between the ledger and the bank statement.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository entries;

    public LedgerService(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    /** One side of a journal, before it is validated and persisted. */
    public record Leg(LedgerEntry.Account account, LedgerEntry.Direction direction, long amount) {
        public static Leg debit(LedgerEntry.Account account, long amount) {
            return new Leg(account, LedgerEntry.Direction.DEBIT, amount);
        }

        public static Leg credit(LedgerEntry.Account account, long amount) {
            return new Leg(account, LedgerEntry.Direction.CREDIT, amount);
        }
    }

    /**
     * Post a balanced journal, or throw.
     *
     * <p>This runs in the caller's transaction on purpose. If the payment state change rolls back,
     * so do its ledger entries — a ledger that can disagree with the thing it describes is worse
     * than no ledger.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String post(String merchantId, String currency, String refType, String refId,
                       String memo, List<Leg> legs) {
        long balance = 0;
        for (Leg leg : legs) {
            balance += leg.direction() == LedgerEntry.Direction.DEBIT ? leg.amount() : -leg.amount();
        }
        if (balance != 0) {
            throw new IllegalStateException(
                    "Unbalanced journal for " + refType + " " + refId + ": debits minus credits = " + balance);
        }

        String journalId = Ids.generate("jrn");
        List<LedgerEntry> rows = new ArrayList<>();
        for (Leg leg : legs) {
            rows.add(new LedgerEntry(Ids.generate("le"), journalId, leg.account(), leg.direction(),
                    leg.amount(), currency, merchantId, refType, refId, memo));
        }
        entries.saveAll(rows);
        log.debug("Posted journal {} ({} legs) for {} {}", journalId, rows.size(), refType, refId);
        return journalId;
    }

    /**
     * A capture: the gateway now has a claim on the scheme, owes the merchant its share, and keeps
     * the rest as revenue.
     */
    public String recordCapture(String merchantId, String currency, String paymentIntentId,
                                long gross, long fee) {
        return post(merchantId, currency, "payment_intent", paymentIntentId,
                "Capture of " + paymentIntentId,
                List.of(
                        Leg.debit(LedgerEntry.Account.SCHEME_RECEIVABLE, gross),
                        Leg.credit(LedgerEntry.Account.MERCHANT_PAYABLE, gross - fee),
                        Leg.credit(LedgerEntry.Account.FEE_INCOME, fee)));
    }

    /**
     * A refund reduces what we owe the merchant and creates an obligation to the cardholder.
     *
     * <p>Deliberately modelled as a fresh journal rather than a reversal of the capture. The
     * original still happened; pretending otherwise would erase history.
     */
    public String recordRefund(String merchantId, String currency, String refundId, long amount) {
        return post(merchantId, currency, "refund", refundId, "Refund " + refundId,
                List.of(
                        Leg.debit(LedgerEntry.Account.MERCHANT_PAYABLE, amount),
                        Leg.credit(LedgerEntry.Account.REFUND_PAYABLE, amount)));
    }

    /** A chargeback freezes funds pending the outcome; it does not yet decide who keeps them. */
    public String recordDisputeOpened(String merchantId, String currency, String disputeId,
                                      long amount, long fee) {
        return post(merchantId, currency, "dispute", disputeId, "Dispute opened " + disputeId,
                List.of(
                        Leg.debit(LedgerEntry.Account.MERCHANT_PAYABLE, amount + fee),
                        Leg.credit(LedgerEntry.Account.DISPUTE_HOLDING, amount),
                        Leg.credit(LedgerEntry.Account.FEE_INCOME, fee)));
    }

    /** Merchant won: the held funds go back. The dispute fee does not. */
    public String recordDisputeWon(String merchantId, String currency, String disputeId, long amount) {
        return post(merchantId, currency, "dispute", disputeId, "Dispute won " + disputeId,
                List.of(
                        Leg.debit(LedgerEntry.Account.DISPUTE_HOLDING, amount),
                        Leg.credit(LedgerEntry.Account.MERCHANT_PAYABLE, amount)));
    }

    /** Merchant lost: the held funds leave the system towards the issuer. */
    public String recordDisputeLost(String merchantId, String currency, String disputeId, long amount) {
        return post(merchantId, currency, "dispute", disputeId, "Dispute lost " + disputeId,
                List.of(
                        Leg.debit(LedgerEntry.Account.DISPUTE_HOLDING, amount),
                        Leg.credit(LedgerEntry.Account.SCHEME_RECEIVABLE, amount)));
    }

    /** A payout settles the payable by moving real cash out of the gateway's bank account. */
    public String recordPayout(String merchantId, String currency, String settlementId, long amount) {
        return post(merchantId, currency, "payout", settlementId, "Payout " + settlementId,
                List.of(
                        Leg.debit(LedgerEntry.Account.MERCHANT_PAYABLE, amount),
                        Leg.credit(LedgerEntry.Account.SETTLEMENT_CASH, amount)));
    }

    public List<LedgerEntry> forReference(String refId) {
        return entries.findByRefIdOrderByCreatedAtAsc(refId);
    }

    /**
     * Trial balance for a merchant.
     *
     * <p>Running this and checking it comes to zero is the cheapest possible health check on a
     * payment system, and it is what a reconciliation job does every night before anyone is paid.
     */
    public Map<String, Long> trialBalance(String merchantId) {
        Map<String, Long> balances = new LinkedHashMap<>();
        long total = 0;
        for (LedgerEntry e : entries.findByMerchantIdOrderByCreatedAtDesc(merchantId)) {
            balances.merge(e.getAccount().name(), e.signedAmount(), Long::sum);
            total += e.signedAmount();
        }
        balances.put("_TOTAL_MUST_BE_ZERO", total);
        return balances;
    }
}
