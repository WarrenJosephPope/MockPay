package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A person who can log into the dashboard.
 *
 * <p>Deliberately <b>not</b> the same thing as a {@link Merchant}. A merchant is a business; a user
 * is a human. One business has several people — an owner, a developer, someone in support — and one
 * person may belong to several businesses. Collapsing the two by bolting a password column onto
 * {@code merchants} is the shortcut that becomes expensive the first time a second person needs
 * access, because by then the fix is a migration across live data.
 *
 * <p>The link between the two is {@link Membership}, which is also where the role lives.
 *
 * <h2>Argon2, not SHA-256</h2>
 *
 * <p>The exact opposite reasoning to {@link ApiKey}. An API key is 32 random characters, so a fast
 * hash is correct and a slow one would only tax every request. A password is chosen by a human, is
 * short, and is very often reused from somewhere else — so a leaked hash must be as expensive as
 * possible to attack offline. Argon2id is memory-hard, which defeats the GPU and ASIC farms that
 * make fast hashes worthless for this purpose.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    /** Lower-cased on the way in. Case-sensitive emails are a support burden and a login trap. */
    @Column(unique = true, nullable = false)
    private String email;

    /** Argon2id. Never reversible, never logged, never returned by any endpoint. */
    @Column(nullable = false)
    private String passwordHash;

    private String name;

    /**
     * Present but unused for now. Recorded so that adding email verification later is a behaviour
     * change rather than a migration.
     */
    private Instant emailVerifiedAt;

    /** TOTP shared secret. Null until the user enables MFA. */
    private String mfaSecret;

    private Instant lastLoginAt;

    /**
     * Consecutive failed logins, and the lock that follows.
     *
     * <p>Rate limiting by IP is not enough on its own: a distributed attempt against one known
     * email address comes from many addresses. Counting per account is what actually stops
     * credential stuffing.
     */
    private int failedLoginAttempts;
    private Instant lockedUntil;

    private Instant createdAt = Instant.now();

    protected User() {
    }

    public User(String id, String email, String passwordHash, String name) {
        this.id = id;
        this.email = email.toLowerCase().trim();
        this.passwordHash = passwordHash;
        this.name = name;
    }

    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
