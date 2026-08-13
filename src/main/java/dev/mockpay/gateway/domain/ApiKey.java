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
 * An API credential, stored so that a database leak does not hand over the account.
 *
 * <p>Keys used to be plaintext columns on {@link Merchant}. That was survivable while they were
 * fixed demo strings, but the moment a dashboard can create and display them it becomes the single
 * worst thing in the schema: anyone who reads the table can move money.
 *
 * <h2>Why a fast hash and not bcrypt</h2>
 *
 * <p>Passwords get bcrypt or Argon2 because humans choose guessable ones, and the deliberate
 * slowness is what makes a stolen hash expensive to crack. An API key is different: it is 32 random
 * characters from a CSPRNG, so there is nothing to guess — brute-forcing it is infeasible against
 * any hash function. Using bcrypt here would buy no security and would add its deliberate ~100ms to
 * <em>every single API request</em>, which is a self-inflicted denial of service.
 *
 * <p>So: SHA-256 over the key, stored in an indexed unique column. Verification is a single
 * equality lookup rather than a scan-and-compare, which matters because this runs on every request.
 *
 * <h2>Secret vs publishable</h2>
 *
 * <p>Secret keys are shown <b>once</b>, at creation, and never again — only {@link #keyPrefix}
 * survives for display, the way every real gateway does it. Publishable keys are not secret by
 * design (they ship to browsers), so their plaintext is retained and can always be shown.
 */
@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "idx_apikey_hash", columnList = "keyHash", unique = true),
        @Index(name = "idx_apikey_merchant", columnList = "merchantId")
})
public class ApiKey {

    public enum Type {
        /** `sk_...` — server-side only, full authority over the account. */
        SECRET,
        /** `pk_...` — safe in a browser; can tokenise, cannot move money. */
        PUBLISHABLE
    }

    @Id
    private String id;

    private String merchantId;

    @Enumerated(EnumType.STRING)
    private Type type;

    /** SHA-256 of the full key. The only copy of a secret key that survives creation. */
    @Column(nullable = false, unique = true)
    private String keyHash;

    /** First few characters, for display: {@code sk_test_51H8xK2…}. Not usable for auth. */
    private String keyPrefix;

    /**
     * Plaintext, populated for publishable keys only. Deliberately null for secret keys — if this
     * column is ever non-null for a SECRET row, something has gone badly wrong.
     */
    private String publicValue;

    /** Human label, so a merchant can tell "CI" from "production" when revoking. */
    private String name;

    /** Updated opportunistically on use. Answers "is this key still in use?" before revoking it. */
    private Instant lastUsedAt;

    /** Set rather than deleted: an audit trail of which key was live when is worth keeping. */
    private Instant revokedAt;

    private Instant createdAt = Instant.now();

    protected ApiKey() {
    }

    public ApiKey(String id, String merchantId, Type type, String keyHash, String keyPrefix,
                  String publicValue, String name) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.publicValue = publicValue;
        this.name = name;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Type getType() {
        return type;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getPublicValue() {
        return publicValue;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
