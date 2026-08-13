package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single-use, short-lived credential that lets someone set a new password without knowing the old
 * one.
 *
 * <p>Which makes it, for the duration of its life, equivalent to the password itself. Three
 * properties follow from that and none of them is optional:
 *
 * <ul>
 *   <li><b>Only the hash is stored.</b> The plaintext exists in the user's inbox and nowhere else,
 *       so a database dump does not yield a set of working account-takeover links.
 *   <li><b>It expires quickly.</b> An hour, not a week. The window in which a token intercepted from
 *       an inbox is useful should be small.
 *   <li><b>It is consumed on use</b>, and using one invalidates every other outstanding token for
 *       that user — otherwise an attacker who requested a reset before the real owner did still
 *       holds a live link afterwards.
 * </ul>
 */
@Entity
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_reset_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_reset_token_user", columnList = "userId,usedAt")
})
public class PasswordResetToken {

    @Id
    private String id;

    private String userId;

    /** SHA-256 of the emailed token. High entropy, so a fast indexed hash is the right choice. */
    private String tokenHash;

    private Instant expiresAt;
    private Instant usedAt;

    /** Where the request came from, so an unexpected reset can be investigated. */
    private String requestedIp;

    private Instant createdAt = Instant.now();

    protected PasswordResetToken() {
    }

    public PasswordResetToken(String id, String userId, String tokenHash, Instant expiresAt,
                              String requestedIp) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.requestedIp = requestedIp;
    }

    public boolean isUsable() {
        return usedAt == null && Instant.now().isBefore(expiresAt);
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public String getRequestedIp() {
        return requestedIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
