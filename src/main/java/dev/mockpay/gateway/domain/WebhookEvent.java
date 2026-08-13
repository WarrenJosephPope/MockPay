package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An outbound event, stored before it is sent. This is the transactional outbox pattern.
 *
 * <p>The failure this prevents is subtle and common: you capture a payment, commit the database
 * transaction, then call the merchant's webhook — and the process dies in between. The merchant
 * never learns the payment succeeded and ships nothing. Or the reverse: you send the webhook first,
 * then the transaction rolls back, and the merchant ships goods for a payment that does not exist.
 *
 * <p>Writing the event into the same database transaction as the state change makes the two atomic.
 * A separate dispatcher then delivers it. That buys at-least-once delivery, which is the strongest
 * guarantee available across a network — so consumers must be idempotent, keyed on {@link #id}.
 */
@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_webhook_dispatch", columnList = "status,nextAttemptAt")
})
public class WebhookEvent {

    public enum Status {
        PENDING,
        DELIVERED,
        /** Retrying; last attempt failed but the budget is not exhausted. */
        RETRYING,
        /** Retry budget exhausted. Needs a human or a manual replay. */
        DEAD
    }

    @Id
    private String id;

    private String merchantId;

    /** e.g. payment_intent.succeeded, charge.refunded, dispute.created */
    private String type;

    @Column(length = 8000)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private int attempts;
    private Instant nextAttemptAt = Instant.now();

    private Integer lastResponseStatus;
    @Column(length = 1000)
    private String lastError;

    private String destinationUrl;

    private Instant createdAt = Instant.now();
    private Instant deliveredAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(String id, String merchantId, String type, String payloadJson,
                        String destinationUrl) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.payloadJson = payloadJson;
        this.destinationUrl = destinationUrl;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getType() {
        return type;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Integer getLastResponseStatus() {
        return lastResponseStatus;
    }

    public void setLastResponseStatus(Integer lastResponseStatus) {
        this.lastResponseStatus = lastResponseStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getDestinationUrl() {
        return destinationUrl;
    }

    public void setDestinationUrl(String destinationUrl) {
        this.destinationUrl = destinationUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
}
