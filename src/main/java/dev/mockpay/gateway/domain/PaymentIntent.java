package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * The order-level object that tracks one attempt to collect one amount of money.
 *
 * <p>This is the single most important design decision in a modern gateway. Older APIs exposed a
 * "create charge" call that either succeeded or failed. That model broke the moment authentication
 * became interactive: with 3-D Secure or a UPI collect request, the payment leaves your server,
 * lives in the customer's banking app for a while, and comes back minutes later. A one-shot call
 * has nowhere to put that intermediate state.
 *
 * <p>A PaymentIntent is instead a long-lived state machine the client polls or subscribes to. The
 * same object survives a declined attempt, a step-up challenge, and a retry on a different
 * instrument, which means the merchant has exactly one id to reconcile against for the order.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public enum Status {
        /** Created, but no instrument attached yet. */
        REQUIRES_PAYMENT_METHOD,
        /** Instrument attached; waiting for an explicit confirm. */
        REQUIRES_CONFIRMATION,
        /** The customer must do something: 3DS challenge, approve a UPI collect, finish a redirect. */
        REQUIRES_ACTION,
        /** Handed to the rail; outcome not yet known. Terminal-ish for the caller, not for us. */
        PROCESSING,
        /** Authorised. Funds are held on the customer's line of credit but not yet claimed. */
        REQUIRES_CAPTURE,
        /** Captured (or auto-captured). Money is committed and will settle. */
        SUCCEEDED,
        /** Abandoned by the merchant or the customer; any authorisation has been voided. */
        CANCELED,
        /** The rail said no and there is nothing left to retry on this attempt. */
        FAILED
    }

    public enum CaptureMethod {
        /** Authorise and capture in one step. Right for digital goods that ship instantly. */
        AUTOMATIC,
        /** Authorise now, capture when you actually ship. Right for physical goods. */
        MANUAL
    }

    /**
     * Legal transitions. Encoding these in one place, rather than as scattered {@code if} checks,
     * is what stops a concurrent webhook and a manual capture from driving the object into a state
     * that accounting cannot explain.
     */
    private static final java.util.Map<Status, Set<Status>> ALLOWED = java.util.Map.of(
            // Includes the terminal states because create-and-confirm in a single call takes an
            // intent from here to authorised (or captured) without passing through the
            // intermediate states a two-step integration would see.
            Status.REQUIRES_PAYMENT_METHOD,
            EnumSet.of(Status.REQUIRES_CONFIRMATION, Status.PROCESSING, Status.REQUIRES_ACTION,
                    Status.REQUIRES_CAPTURE, Status.SUCCEEDED, Status.CANCELED, Status.FAILED),
            Status.REQUIRES_CONFIRMATION,
            EnumSet.of(Status.PROCESSING, Status.REQUIRES_ACTION, Status.REQUIRES_CAPTURE,
                    Status.SUCCEEDED, Status.CANCELED, Status.FAILED, Status.REQUIRES_PAYMENT_METHOD),
            Status.REQUIRES_ACTION,
            EnumSet.of(Status.PROCESSING, Status.REQUIRES_CAPTURE, Status.SUCCEEDED,
                    Status.CANCELED, Status.FAILED, Status.REQUIRES_PAYMENT_METHOD),
            Status.PROCESSING,
            EnumSet.of(Status.REQUIRES_CAPTURE, Status.SUCCEEDED, Status.FAILED,
                    Status.CANCELED, Status.REQUIRES_PAYMENT_METHOD),
            Status.REQUIRES_CAPTURE,
            EnumSet.of(Status.SUCCEEDED, Status.CANCELED, Status.FAILED),
            Status.SUCCEEDED, EnumSet.noneOf(Status.class),
            Status.CANCELED, EnumSet.noneOf(Status.class),
            Status.FAILED, EnumSet.of(Status.REQUIRES_PAYMENT_METHOD, Status.REQUIRES_CONFIRMATION));

    @Id
    private String id;

    private String merchantId;

    /** Minor units. See {@link dev.mockpay.gateway.support.Money}. */
    private long amount;

    private String currency;

    /** How much of {@link #amount} is still available to capture. */
    private long amountCapturable;

    /** How much has actually been captured. */
    private long amountReceived;

    private long amountRefunded;

    @Enumerated(EnumType.STRING)
    private Status status = Status.REQUIRES_PAYMENT_METHOD;

    @Enumerated(EnumType.STRING)
    private CaptureMethod captureMethod = CaptureMethod.AUTOMATIC;

    /**
     * Scoped credential for the browser. Lets an untrusted front end confirm <em>this one</em>
     * intent without ever holding a secret key.
     */
    private String clientSecret;

    private String paymentMethodId;

    @Enumerated(EnumType.STRING)
    private PaymentMethod.Type paymentMethodType;

    // ---- Rail results ------------------------------------------------------
    /** Issuer's approval code, DE 38 in ISO 8583. Six alphanumeric characters. */
    private String authorizationCode;

    /**
     * The network's own id for the transaction. Passing it back on the next charge is what tells
     * the issuer "this is the follow-up to an authenticated payment", which is how subscriptions
     * stay exempt from re-authentication.
     */
    private String networkTransactionId;

    private String acquirerId;

    private String threeDsStatus;
    /** Electronic Commerce Indicator — encodes who carries fraud liability. */
    private String threeDsEci;

    private String lastErrorCode;
    private String lastErrorMessage;
    /** Issuer's raw reason, e.g. {@code insufficient_funds}. Drives retry strategy. */
    private String lastDeclineCode;

    private Integer riskScore;
    private String riskLevel;

    @Column(length = 2000)
    private String nextActionUrl;
    private String nextActionType;

    private String description;
    private String customerRef;
    private String statementDescriptor;

    @Column(length = 4000)
    private String metadataJson;

    private long applicationFee;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant capturedAt;
    private Instant canceledAt;

    /**
     * Optimistic lock. Two concurrent confirms on the same intent — a customer double-clicking
     * while a webhook lands — will collide here rather than both authorising.
     */
    @Version
    private Long version;

    protected PaymentIntent() {
    }

    public PaymentIntent(String id, String merchantId, long amount, String currency,
                         CaptureMethod captureMethod, String clientSecret) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency.toUpperCase();
        this.captureMethod = captureMethod;
        this.clientSecret = clientSecret;
    }

    public boolean canTransitionTo(Status target) {
        return status == target || ALLOWED.getOrDefault(status, Set.of()).contains(target);
    }

    public void transitionTo(Status target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal payment intent transition: " + status + " -> " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.CANCELED;
    }

    public long refundableAmount() {
        return amountReceived - amountRefunded;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAmountCapturable() {
        return amountCapturable;
    }

    public void setAmountCapturable(long amountCapturable) {
        this.amountCapturable = amountCapturable;
    }

    public long getAmountReceived() {
        return amountReceived;
    }

    public void setAmountReceived(long amountReceived) {
        this.amountReceived = amountReceived;
    }

    public long getAmountRefunded() {
        return amountRefunded;
    }

    public void setAmountRefunded(long amountRefunded) {
        this.amountRefunded = amountRefunded;
    }

    public Status getStatus() {
        return status;
    }

    public CaptureMethod getCaptureMethod() {
        return captureMethod;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public void setPaymentMethodId(String paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public PaymentMethod.Type getPaymentMethodType() {
        return paymentMethodType;
    }

    public void setPaymentMethodType(PaymentMethod.Type paymentMethodType) {
        this.paymentMethodType = paymentMethodType;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getNetworkTransactionId() {
        return networkTransactionId;
    }

    public void setNetworkTransactionId(String networkTransactionId) {
        this.networkTransactionId = networkTransactionId;
    }

    public String getAcquirerId() {
        return acquirerId;
    }

    public void setAcquirerId(String acquirerId) {
        this.acquirerId = acquirerId;
    }

    public String getThreeDsStatus() {
        return threeDsStatus;
    }

    public void setThreeDsStatus(String threeDsStatus) {
        this.threeDsStatus = threeDsStatus;
    }

    public String getThreeDsEci() {
        return threeDsEci;
    }

    public void setThreeDsEci(String threeDsEci) {
        this.threeDsEci = threeDsEci;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public String getLastDeclineCode() {
        return lastDeclineCode;
    }

    public void setLastDeclineCode(String lastDeclineCode) {
        this.lastDeclineCode = lastDeclineCode;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getNextActionUrl() {
        return nextActionUrl;
    }

    public void setNextActionUrl(String nextActionUrl) {
        this.nextActionUrl = nextActionUrl;
    }

    public String getNextActionType() {
        return nextActionType;
    }

    public void setNextActionType(String nextActionType) {
        this.nextActionType = nextActionType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public String getStatementDescriptor() {
        return statementDescriptor;
    }

    public void setStatementDescriptor(String statementDescriptor) {
        this.statementDescriptor = statementDescriptor;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public long getApplicationFee() {
        return applicationFee;
    }

    public void setApplicationFee(long applicationFee) {
        this.applicationFee = applicationFee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public void setCanceledAt(Instant canceledAt) {
        this.canceledAt = canceledAt;
    }

    public Long getVersion() {
        return version;
    }
}
