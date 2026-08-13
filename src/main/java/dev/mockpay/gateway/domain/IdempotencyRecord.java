package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The record that makes a retry safe.
 *
 * <p>The client sends {@code Idempotency-Key: <uuid>} with a mutating request. The server stores
 * the key before doing any work. If the same key arrives again, the stored response is replayed
 * instead of the operation being run twice.
 *
 * <p>Two details separate a correct implementation from a dangerous one:
 *
 * <ul>
 *   <li>The <b>request fingerprint</b>. If the same key arrives with a <em>different</em> body, that
 *       is a client bug, and silently returning the old response would hide it. Reject with 422.
 *       Without this check, a client that reuses a key across two different charges gets the wrong
 *       one back and never notices.
 *   <li>The <b>in-progress state</b>. Two identical requests can arrive concurrently — a phone on a
 *       flaky connection retrying while the first request is still running. The second must be told
 *       to wait (409), not allowed to start a parallel authorisation.
 * </ul>
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }

    /** Composite of merchant id and client key — keys are scoped per account, never global. */
    @Id
    private String id;

    private String merchantId;
    private String idempotencyKey;

    /** SHA-256 of method + path + body. Detects key reuse with different parameters. */
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    private State state = State.IN_PROGRESS;

    private Integer responseStatus;

    @Column(length = 8000)
    private String responseBody;

    /** Id of whatever was created, for logging and support. */
    private String resourceId;

    private Instant createdAt = Instant.now();
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String id, String merchantId, String idempotencyKey,
                             String requestFingerprint) {
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
