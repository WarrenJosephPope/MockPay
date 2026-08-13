package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A merchant account — the gateway's tenant boundary.
 *
 * <p>Every object in the system hangs off a merchant id, and every query filters on it. In a real
 * gateway this is the single most safety-critical predicate in the codebase: forgetting it on one
 * endpoint leaks another business's payment data.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    private String id;

    private String name;

    /** Safe to embed in a browser. Identifies the account, cannot move money. */
    @Column(unique = true)
    private String publishableKey;

    /** Server-side only. Full authority over the account. */
    @Column(unique = true)
    private String secretKey;

    /** Separate secret, used only to sign outbound webhooks. */
    private String webhookSecret;

    private String webhookUrl;

    private String settlementCurrency;

    /** ISO 18245 merchant category code. Drives interchange and issuer risk models. */
    private String mcc;

    private String country;

    private Instant createdAt = Instant.now();

    protected Merchant() {
    }

    public Merchant(String id, String name, String publishableKey, String secretKey,
                    String webhookSecret, String webhookUrl, String settlementCurrency,
                    String mcc, String country) {
        this.id = id;
        this.name = name;
        this.publishableKey = publishableKey;
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.webhookUrl = webhookUrl;
        this.settlementCurrency = settlementCurrency;
        this.mcc = mcc;
        this.country = country;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public String getMcc() {
        return mcc;
    }

    public String getCountry() {
        return country;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
