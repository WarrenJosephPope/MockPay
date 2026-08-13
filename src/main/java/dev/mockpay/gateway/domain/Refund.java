package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A refund is not the reverse of a payment — it is a second, independent payment in the opposite
 * direction.
 *
 * <p>This distinction has real consequences. The original interchange is usually not returned, so
 * a refunded sale costs the merchant money. The refund settles on its own schedule, days after the
 * sale did. And because it is a separate rail message, it can fail on its own, which is why it
 * needs a status of its own rather than a boolean on the payment.
 *
 * <p>Before capture, the right operation is a <em>void</em> (reversal), not a refund: it releases
 * the hold, usually costs nothing, and never appears on the customer's statement.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status {
        PENDING,
        SUCCEEDED,
        FAILED,
        /** Refund was accepted, then bounced back by the issuer (closed account, etc.). */
        REVERSED
    }

    @Id
    private String id;

    private String paymentIntentId;
    private String merchantId;

    private long amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    /** requested_by_customer | duplicate | fraudulent | product_not_received */
    private String reason;

    private String failureReason;
    private String rrn;

    private Instant createdAt = Instant.now();
    private Instant settledAt;

    protected Refund() {
    }

    public Refund(String id, String paymentIntentId, String merchantId, long amount,
                  String currency, String reason) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
    }

    public String getId() {
        return id;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getRrn() {
        return rrn;
    }

    public void setRrn(String rrn) {
        this.rrn = rrn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
