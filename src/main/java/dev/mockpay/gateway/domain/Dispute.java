package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A chargeback: the cardholder went to their issuer instead of to the merchant.
 *
 * <p>The money is pulled back from the merchant immediately and held while the case runs. The
 * merchant then has a fixed window — Visa's dispute-response clock is measured in days, not weeks —
 * to submit evidence. Miss it and the case is lost by default.
 *
 * <p>Disputes are also why gateways hold reserves. If a merchant disappears owing chargebacks, the
 * acquirer is liable for them, which is the actual reason onboarding involves underwriting.
 */
@Entity
@Table(name = "disputes")
public class Dispute {

    public enum Status {
        /** Evidence not yet submitted. The clock is running. */
        NEEDS_RESPONSE,
        /** Evidence submitted; issuer is reviewing. */
        UNDER_REVIEW,
        /** Issuer accepted the evidence. Funds are returned to the merchant. */
        WON,
        /** Issuer rejected it, or the window expired. Funds stay with the cardholder. */
        LOST,
        /** Merchant chose not to fight it. */
        ACCEPTED
    }

    @Id
    private String id;

    private String paymentIntentId;
    private String merchantId;

    private long amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private Status status = Status.NEEDS_RESPONSE;

    /** Network reason code, e.g. Visa 10.4 "Other Fraud - Card Absent Environment". */
    private String reasonCode;
    private String reasonDescription;

    /** fraud | authorization | processing_error | consumer_dispute */
    private String category;

    /** Non-refundable fee the acquirer charges regardless of who wins. */
    private long disputeFee;

    @Column(length = 4000)
    private String evidenceJson;

    private Instant evidenceDueBy;
    private Instant createdAt = Instant.now();
    private Instant resolvedAt;

    protected Dispute() {
    }

    public Dispute(String id, String paymentIntentId, String merchantId, long amount,
                   String currency, String reasonCode, String reasonDescription,
                   String category, long disputeFee, Instant evidenceDueBy) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.reasonCode = reasonCode;
        this.reasonDescription = reasonDescription;
        this.category = category;
        this.disputeFee = disputeFee;
        this.evidenceDueBy = evidenceDueBy;
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

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDescription() {
        return reasonDescription;
    }

    public String getCategory() {
        return category;
    }

    public long getDisputeFee() {
        return disputeFee;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public Instant getEvidenceDueBy() {
        return evidenceDueBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
