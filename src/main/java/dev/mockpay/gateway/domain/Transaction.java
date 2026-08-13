package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One round trip to a rail. This is the gateway's audit log.
 *
 * <p>A PaymentIntent says what the merchant wanted; a Transaction says what actually went out on
 * the wire and what came back. You need both. When an issuer insists it never received an
 * authorisation, the retrieval reference number and system trace audit number stored here are the
 * evidence, and they are the fields the acquirer's support desk will ask for by name.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    public enum Type {
        AUTHORIZATION,
        CAPTURE,
        VOID,
        REFUND,
        /** 3-D Secure authentication, which happens before authorisation. */
        AUTHENTICATION,
        /** UPI collect request sent to the payer's app. */
        COLLECT,
        INQUIRY
    }

    public enum Outcome {
        APPROVED,
        DECLINED,
        PENDING,
        ERROR
    }

    @Id
    private String id;

    private String paymentIntentId;
    private String merchantId;

    @Enumerated(EnumType.STRING)
    private Type type;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private long amount;
    private String currency;

    // ---- ISO 8583 style fields --------------------------------------------
    /** Message Type Indicator, e.g. 0100 authorisation request, 0110 response. */
    private String mti;
    /** DE 3. Six digits: transaction type + from-account + to-account. */
    private String processingCode;
    /** DE 11. Unique per acquirer per day; how a reversal finds its original. */
    private String stan;
    /** DE 37. Twelve characters; the id that appears on the cardholder statement. */
    private String rrn;
    /** DE 38. Issuer's approval code. */
    private String authCode;
    /** DE 39. "00" is approved; everything else is a story. */
    private String responseCode;
    private String responseText;

    private String railName;
    private String acquirerId;
    private long latencyMs;

    /** Pretty-printed pseudo-8583 request, so you can read what a real message looks like. */
    @Column(length = 4000)
    private String requestDump;

    @Column(length = 4000)
    private String responseDump;

    private Instant createdAt = Instant.now();

    protected Transaction() {
    }

    public Transaction(String id, String paymentIntentId, String merchantId, Type type,
                       long amount, String currency) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
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

    public Type getType() {
        return type;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMti() {
        return mti;
    }

    public void setMti(String mti) {
        this.mti = mti;
    }

    public String getProcessingCode() {
        return processingCode;
    }

    public void setProcessingCode(String processingCode) {
        this.processingCode = processingCode;
    }

    public String getStan() {
        return stan;
    }

    public void setStan(String stan) {
        this.stan = stan;
    }

    public String getRrn() {
        return rrn;
    }

    public void setRrn(String rrn) {
        this.rrn = rrn;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseText() {
        return responseText;
    }

    public void setResponseText(String responseText) {
        this.responseText = responseText;
    }

    public String getRailName() {
        return railName;
    }

    public void setRailName(String railName) {
        this.railName = railName;
    }

    public String getAcquirerId() {
        return acquirerId;
    }

    public void setAcquirerId(String acquirerId) {
        this.acquirerId = acquirerId;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getRequestDump() {
        return requestDump;
    }

    public void setRequestDump(String requestDump) {
        this.requestDump = requestDump;
    }

    public String getResponseDump() {
        return responseDump;
    }

    public void setResponseDump(String responseDump) {
        this.responseDump = responseDump;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
