package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One line of double-entry bookkeeping. Append-only, never updated, never deleted.
 *
 * <p>The rule the whole system rests on: every journal (a group of entries sharing a
 * {@code journalId}) must sum to zero. Money is only ever moved between accounts, never created or
 * destroyed. If a bug tries to credit a merchant without debiting anything, the write is rejected
 * rather than silently inventing funds — this is the invariant Uber's ledger calls "zero-sum by
 * design", and it is what makes the ledger trustworthy enough to reconcile against a bank statement.
 *
 * <p>Corrections are new entries that reverse old ones. Editing history would mean the ledger no
 * longer explains how the balance got where it is.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_journal", columnList = "journalId"),
        @Index(name = "idx_ledger_account", columnList = "account"),
        @Index(name = "idx_ledger_ref", columnList = "refId")
})
public class LedgerEntry {

    public enum Direction {
        DEBIT,
        CREDIT
    }

    /**
     * The chart of accounts. Deliberately small, but it is the real shape: what the scheme owes us,
     * what we owe the merchant, what we have kept as fees, what is frozen against disputes.
     */
    public enum Account {
        /** Claim on the card scheme / UPI switch between authorisation and settlement. */
        SCHEME_RECEIVABLE,
        /** What the gateway owes the merchant but has not paid out yet. */
        MERCHANT_PAYABLE,
        /** Gateway revenue. */
        FEE_INCOME,
        /** Money owed back to customers for refunds in flight. */
        REFUND_PAYABLE,
        /** Funds frozen while a chargeback is contested. */
        DISPUTE_HOLDING,
        /** The gateway's own bank account; where payouts leave from. */
        SETTLEMENT_CASH
    }

    @Id
    private String id;

    /** Groups the debits and credits of one economic event. Sums to zero. */
    private String journalId;

    @Enumerated(EnumType.STRING)
    private Account account;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    /** Always positive. Direction carries the sign. */
    private long amount;

    private String currency;
    private String merchantId;

    /** What caused this entry: payment_intent | refund | dispute | payout | fee */
    private String refType;
    private String refId;

    private String memo;

    private Instant createdAt = Instant.now();

    protected LedgerEntry() {
    }

    public LedgerEntry(String id, String journalId, Account account, Direction direction,
                       long amount, String currency, String merchantId,
                       String refType, String refId, String memo) {
        if (amount < 0) {
            throw new IllegalArgumentException("Ledger amounts are unsigned; use direction instead");
        }
        this.id = id;
        this.journalId = journalId;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.merchantId = merchantId;
        this.refType = refType;
        this.refId = refId;
        this.memo = memo;
    }

    /** Signed value used to prove a journal balances. */
    public long signedAmount() {
        return direction == Direction.DEBIT ? amount : -amount;
    }

    public String getId() {
        return id;
    }

    public String getJournalId() {
        return journalId;
    }

    public Account getAccount() {
        return account;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getRefType() {
        return refType;
    }

    public String getRefId() {
        return refId;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
