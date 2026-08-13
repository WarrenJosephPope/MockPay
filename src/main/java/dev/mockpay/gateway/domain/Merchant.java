package dev.mockpay.gateway.domain;

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

    // API keys live in `api_keys` and webhook destinations in `webhook_endpoints`. They were
    // columns here until Phase 2; moving them out is what allows several keys per account, key
    // rotation without downtime, several webhook endpoints, and — most importantly — storing
    // secret keys as hashes rather than as readable strings.

    private String settlementCurrency;

    /** ISO 18245 merchant category code. Drives interchange and issuer risk models. */
    private String mcc;

    private String country;

    private Instant createdAt = Instant.now();

    protected Merchant() {
    }

    public Merchant(String id, String name, String settlementCurrency, String mcc, String country) {
        this.id = id;
        this.name = name;
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

    public void setName(String name) {
        this.name = name;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public String getMcc() {
        return mcc;
    }

    public void setMcc(String mcc) {
        this.mcc = mcc;
    }

    public String getCountry() {
        return country;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
