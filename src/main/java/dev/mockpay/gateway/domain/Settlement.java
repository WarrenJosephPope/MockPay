package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A payout batch: everything that happened in one period, netted down to one bank transfer.
 *
 * <p>Card networks do not move money per transaction. They accumulate a day's clearing records and
 * settle the <em>net</em> position between each pair of institutions once, which is why a merchant
 * sees one deposit rather than four hundred. The lag between "captured" and "in the bank" is that
 * batch cycle plus the acquirer's own hold period.
 *
 * <p>Net, here, means gross sales minus fees minus refunds minus chargebacks. A bad enough day can
 * make it negative, which is why acquirers hold reserves.
 */
@Entity
@Table(name = "settlements")
public class Settlement {

    public enum Status {
        /** Accumulating; the period is still open. */
        OPEN,
        /** Period closed, amounts fixed, waiting for the payout date. */
        PENDING_PAYOUT,
        PAID,
        FAILED
    }

    @Id
    private String id;

    private String merchantId;
    private String currency;

    private long grossAmount;
    private long feeAmount;
    private long refundAmount;
    private long disputeAmount;
    private long netAmount;

    private int transactionCount;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate expectedPayoutDate;

    private Instant createdAt = Instant.now();
    private Instant paidAt;

    protected Settlement() {
    }

    public Settlement(String id, String merchantId, String currency,
                      LocalDate periodStart, LocalDate periodEnd, LocalDate expectedPayoutDate) {
        this.id = id;
        this.merchantId = merchantId;
        this.currency = currency;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.expectedPayoutDate = expectedPayoutDate;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(long grossAmount) {
        this.grossAmount = grossAmount;
    }

    public long getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(long feeAmount) {
        this.feeAmount = feeAmount;
    }

    public long getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(long refundAmount) {
        this.refundAmount = refundAmount;
    }

    public long getDisputeAmount() {
        return disputeAmount;
    }

    public void setDisputeAmount(long disputeAmount) {
        this.disputeAmount = disputeAmount;
    }

    public long getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(long netAmount) {
        this.netAmount = netAmount;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getExpectedPayoutDate() {
        return expectedPayoutDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }
}
