package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One destination the gateway will POST events to.
 *
 * <p>Replaces the single {@code webhook_url} column on {@link Merchant}, which forced every merchant
 * into exactly one endpoint receiving every event type. Real integrations need more than that: an
 * order service that only cares about {@code payment_intent.succeeded}, a finance service that only
 * wants {@code payout.paid}, and a staging endpoint alongside production.
 *
 * <p>Each endpoint carries <b>its own signing secret</b>. Sharing one secret across endpoints means
 * rotating it breaks every consumer at once, and it means a compromised staging endpoint can forge
 * events to production.
 */
@Entity
@Table(name = "webhook_endpoints", indexes = {
        @Index(name = "idx_endpoint_merchant", columnList = "merchantId")
})
public class WebhookEndpoint {

    @Id
    private String id;

    private String merchantId;

    @Column(length = 2000, nullable = false)
    private String url;

    /** Per-endpoint HMAC secret. */
    @Column(nullable = false)
    private String secret;

    private String description;

    private boolean enabled = true;

    /**
     * Comma-separated event types to deliver, or null/blank for all of them.
     *
     * <p>A denormalised list rather than a join table: it is read on every event emission and never
     * queried by value, so a second table would add a join to the hot path and buy nothing.
     */
    @Column(length = 2000)
    private String eventTypes;

    /**
     * Consecutive delivery failures. Lets the gateway disable an endpoint that has been dead for
     * days instead of retrying into a wall forever.
     */
    private int consecutiveFailures;

    private Instant disabledAt;
    private Instant createdAt = Instant.now();

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(String id, String merchantId, String url, String secret,
                           String description, String eventTypes) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.description = description;
        this.eventTypes = eventTypes;
    }

    /**
     * Whether this endpoint wants a given event type.
     *
     * <p>An empty subscription list means "everything", which is the right default: a merchant who
     * has not thought about filtering should receive all events rather than silently none.
     */
    public boolean subscribesTo(String eventType) {
        if (eventTypes == null || eventTypes.isBlank()) {
            return true;
        }
        return subscribedTypes().contains(eventType);
    }

    public Set<String> subscribedTypes() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.disabledAt = enabled ? null : Instant.now();
    }

    public String getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(String eventTypes) {
        this.eventTypes = eventTypes;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
