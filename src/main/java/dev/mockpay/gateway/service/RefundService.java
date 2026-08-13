package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.Refund;
import dev.mockpay.gateway.domain.Transaction;
import dev.mockpay.gateway.rails.CardNetworkSimulator;
import dev.mockpay.gateway.rails.RailResult;
import dev.mockpay.gateway.rails.UpiSimulator;
import dev.mockpay.gateway.rails.WalletSimulator;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.repo.PaymentMethodRepository;
import dev.mockpay.gateway.repo.RefundRepository;
import dev.mockpay.gateway.repo.TransactionRepository;
import dev.mockpay.gateway.support.Ids;
import dev.mockpay.gateway.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Giving money back — a separate transaction on the rail, and a separate journal in the books. */
@Service
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentIntentRepository intents;
    private final PaymentMethodRepository paymentMethods;
    private final MerchantRepository merchants;
    private final TransactionRepository transactions;
    private final CardNetworkSimulator cardNetwork;
    private final UpiSimulator upi;
    private final WalletSimulator wallet;
    private final LedgerService ledger;
    private final EventService events;
    private final PaymentService payments;

    public RefundService(RefundRepository refunds, PaymentIntentRepository intents,
                         PaymentMethodRepository paymentMethods, MerchantRepository merchants,
                         TransactionRepository transactions, CardNetworkSimulator cardNetwork,
                         UpiSimulator upi, WalletSimulator wallet, LedgerService ledger,
                         EventService events, PaymentService payments) {
        this.refunds = refunds;
        this.intents = intents;
        this.paymentMethods = paymentMethods;
        this.merchants = merchants;
        this.transactions = transactions;
        this.cardNetwork = cardNetwork;
        this.upi = upi;
        this.wallet = wallet;
        this.ledger = ledger;
        this.events = events;
        this.payments = payments;
    }

    @Transactional
    public Refund create(String merchantId, String intentId, Long amount, String reason) {
        PaymentIntent intent = payments.mustFind(merchantId, intentId);

        if (intent.getStatus() != PaymentIntent.Status.SUCCEEDED) {
            // The distinction that trips people up: before capture there is nothing to refund,
            // because no money moved. Cancel releases the hold instead, at no cost.
            throw new ApiException(400, "invalid_request_error", "payment_intent_unexpected_state",
                    "Only a succeeded payment can be refunded. This one is "
                            + intent.getStatus().name().toLowerCase()
                            + " — cancel it to release the authorisation instead.");
        }

        long refundable = intent.refundableAmount();
        long toRefund = amount == null ? refundable : amount;

        if (toRefund <= 0 || toRefund > refundable) {
            throw new ApiException(400, "invalid_request_error", "amount_too_large",
                    "At most " + Money.format(refundable, intent.getCurrency())
                            + " is still refundable on this payment.");
        }

        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();

        Refund refund = new Refund(Ids.generate("re"), intentId, merchantId, toRefund,
                intent.getCurrency(), reason == null ? "requested_by_customer" : reason);

        RailResult result = switch (pm.getType()) {
            case UPI -> upi.refund(intent, pm, toRefund);
            case WALLET, NETBANKING -> wallet.refund(intent, pm, toRefund);
            default -> cardNetwork.refund(intent, pm, merchant, toRefund);
        };

        Transaction txn = new Transaction(Ids.generate("txn"), intentId, merchantId,
                Transaction.Type.REFUND, toRefund, intent.getCurrency());
        txn.setOutcome(result.outcome());
        txn.setResponseCode(result.responseCode());
        txn.setResponseText(result.responseText());
        txn.setRrn(result.rrn());
        txn.setRailName(result.railName());
        txn.setAcquirerId(result.acquirerId());
        txn.setLatencyMs(result.latencyMs());
        txn.setRequestDump(truncate(result.requestDump()));
        txn.setResponseDump(truncate(result.responseDump()));
        transactions.save(txn);

        if (!result.approved()) {
            refund.setStatus(Refund.Status.FAILED);
            refund.setFailureReason(result.responseText());
            refunds.save(refund);
            events.emit(merchantId, "refund.failed", snapshot(refund));
            return refund;
        }

        refund.setStatus(Refund.Status.SUCCEEDED);
        refund.setRrn(result.rrn());
        refund.setSettledAt(Instant.now());
        refunds.save(refund);

        intent.setAmountRefunded(intent.getAmountRefunded() + toRefund);
        intent.touch();
        intents.save(intent);

        ledger.recordRefund(merchantId, intent.getCurrency(), refund.getId(), toRefund);

        // A partial refund is materially different news to a merchant than a full one — an
        // accounting system needs to know whether the order is closed.
        String eventType = intent.refundableAmount() == 0 ? "payment_intent.refunded"
                : "payment_intent.partially_refunded";
        events.emit(merchantId, eventType, payments.snapshot(intent));
        events.emit(merchantId, "refund.succeeded", snapshot(refund));
        return refund;
    }

    public Refund mustFind(String merchantId, String refundId) {
        return refunds.findByIdAndMerchantId(refundId, merchantId)
                .orElseThrow(() -> ApiException.notFound("refund"));
    }

    public List<Refund> forPaymentIntent(String merchantId, String intentId) {
        payments.mustFind(merchantId, intentId);
        return refunds.findByPaymentIntentIdOrderByCreatedAtAsc(intentId);
    }

    public Map<String, Object> snapshot(Refund refund) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", refund.getId());
        map.put("object", "refund");
        map.put("payment_intent", refund.getPaymentIntentId());
        map.put("amount", refund.getAmount());
        map.put("currency", refund.getCurrency());
        map.put("status", refund.getStatus().name().toLowerCase());
        map.put("reason", refund.getReason());
        map.put("failure_reason", refund.getFailureReason());
        map.put("rrn", refund.getRrn());
        map.put("created", refund.getCreatedAt().getEpochSecond());
        return map;
    }

    private String truncate(String s) {
        return s == null ? null : s.length() <= 3900 ? s : s.substring(0, 3900);
    }
}
