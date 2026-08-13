package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Server-side state for a payment that has left the building.
 *
 * <p>When a payment needs a 3-D Secure challenge, a UPI collect approval, or a wallet redirect, the
 * customer disappears into someone else's interface. This row is what the gateway holds onto while
 * that happens: it is looked up by an unguessable token when the customer (or the rail) comes back.
 *
 * <p>Two properties matter. It <b>expires</b> — a UPI collect request that nobody approves must
 * time out rather than hang forever, and an authorisation hold cannot be left open indefinitely.
 * And it is <b>single-use</b>: consuming it marks it spent, so a replayed callback cannot approve
 * the same payment twice.
 */
@Entity
@Table(name = "pending_actions")
public class PendingAction {

    public enum Kind {
        THREE_DS_CHALLENGE,
        UPI_COLLECT,
        WALLET_REDIRECT
    }

    @Id
    private String id;

    private String paymentIntentId;
    private String merchantId;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    /** The one-time-code the simulated ACS expects, for 3DS challenges. */
    private String expectedOtp;

    private int otpAttempts;

    /** Where to send the customer once they are done. */
    private String returnUrl;

    private boolean consumed;

    private Instant expiresAt;
    private Instant createdAt = Instant.now();

    protected PendingAction() {
    }

    public PendingAction(String id, String paymentIntentId, String merchantId, Kind kind,
                         String expectedOtp, String returnUrl, Instant expiresAt) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.kind = kind;
        this.expectedOtp = expectedOtp;
        this.returnUrl = returnUrl;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !consumed && !isExpired();
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

    public Kind getKind() {
        return kind;
    }

    public String getExpectedOtp() {
        return expectedOtp;
    }

    public int getOtpAttempts() {
        return otpAttempts;
    }

    public void setOtpAttempts(int otpAttempts) {
        this.otpAttempts = otpAttempts;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
