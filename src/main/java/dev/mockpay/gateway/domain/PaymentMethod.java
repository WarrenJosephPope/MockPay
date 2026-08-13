package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A tokenised payment instrument.
 *
 * <p>Note what is <em>not</em> here: no PAN, no CVV, no UPI PIN. PCI DSS forbids storing the CVV
 * after authorisation at all, and since RBI's card-on-file mandate no Indian entity other than the
 * issuer and the network may store the card number. What survives is a token plus the handful of
 * fields you are allowed to keep for display and reconciliation — brand, last four, expiry.
 *
 * <p>The {@code fingerprint} is a salted hash of the PAN. It lets you recognise "this is the same
 * card as last time" for velocity checks and duplicate-subscription detection, without being able
 * to reverse it back into a card number.
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    public enum Type {
        /** Visa / Mastercard / Amex / RuPay — the four-party model. */
        CARD,
        /** India's Unified Payments Interface — account-to-account, real-time. */
        UPI,
        /** Stored-value or pass-through wallet, reached by redirect. */
        WALLET,
        /** Bank redirect (netbanking / iDEAL / Sofort style). */
        NETBANKING
    }

    @Id
    private String id;

    private String merchantId;

    @Enumerated(EnumType.STRING)
    private Type type;

    // ---- Card fields -------------------------------------------------------
    private String cardBrand;
    private String cardLast4;
    private Integer cardExpMonth;
    private Integer cardExpYear;
    /** debit | credit | prepaid — issuers price and decline these very differently. */
    private String cardFunding;
    private String cardIssuer;
    private String cardCountry;
    /** First 6-8 digits. Identifies the issuer and drives routing decisions. */
    private String cardBin;
    /** Salted hash of the PAN. Stable across tokens, not reversible. */
    private String cardFingerprint;
    /** Network token (Visa VTS / Mastercard MDES surrogate), if provisioned. */
    private String networkToken;

    // ---- UPI fields --------------------------------------------------------
    private String upiVpa;

    // ---- Wallet fields -----------------------------------------------------
    private String walletProvider;

    /**
     * Which simulated issuer behaviour this instrument triggers. Decided once, at tokenisation
     * time, so the PAN never has to be retained to reproduce it.
     */
    private String simulatedBehaviour;

    private Instant createdAt = Instant.now();

    protected PaymentMethod() {
    }

    public static PaymentMethod card(String id, String merchantId, String brand, String last4,
                                     int expMonth, int expYear, String funding, String issuer,
                                     String country, String bin, String fingerprint,
                                     String networkToken, String behaviour) {
        PaymentMethod pm = new PaymentMethod();
        pm.id = id;
        pm.merchantId = merchantId;
        pm.type = Type.CARD;
        pm.cardBrand = brand;
        pm.cardLast4 = last4;
        pm.cardExpMonth = expMonth;
        pm.cardExpYear = expYear;
        pm.cardFunding = funding;
        pm.cardIssuer = issuer;
        pm.cardCountry = country;
        pm.cardBin = bin;
        pm.cardFingerprint = fingerprint;
        pm.networkToken = networkToken;
        pm.simulatedBehaviour = behaviour;
        return pm;
    }

    public static PaymentMethod upi(String id, String merchantId, String vpa, String behaviour) {
        PaymentMethod pm = new PaymentMethod();
        pm.id = id;
        pm.merchantId = merchantId;
        pm.type = Type.UPI;
        pm.upiVpa = vpa;
        pm.simulatedBehaviour = behaviour;
        return pm;
    }

    public static PaymentMethod wallet(String id, String merchantId, String provider, String behaviour) {
        PaymentMethod pm = new PaymentMethod();
        pm.id = id;
        pm.merchantId = merchantId;
        pm.type = Type.WALLET;
        pm.walletProvider = provider;
        pm.simulatedBehaviour = behaviour;
        return pm;
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

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public Integer getCardExpMonth() {
        return cardExpMonth;
    }

    public Integer getCardExpYear() {
        return cardExpYear;
    }

    public String getCardFunding() {
        return cardFunding;
    }

    public String getCardIssuer() {
        return cardIssuer;
    }

    public String getCardCountry() {
        return cardCountry;
    }

    public String getCardBin() {
        return cardBin;
    }

    public String getCardFingerprint() {
        return cardFingerprint;
    }

    public String getNetworkToken() {
        return networkToken;
    }

    public String getUpiVpa() {
        return upiVpa;
    }

    public String getWalletProvider() {
        return walletProvider;
    }

    public String getSimulatedBehaviour() {
        return simulatedBehaviour;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
