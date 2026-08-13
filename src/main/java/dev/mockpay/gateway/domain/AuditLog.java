package dev.mockpay.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Who did what, when, from where.
 *
 * <p>Not optional in a payment system. When a merchant asks why a refund was issued, or how an API
 * key came to exist, the payment tables answer <em>what</em> happened and this answers <em>who</em>.
 * Regulators and card schemes both expect it, and so does anyone investigating an incident.
 *
 * <p>Append-only, like {@link LedgerEntry}, and for the same reason: an audit log that can be edited
 * is not evidence of anything. Nothing in this codebase updates or deletes a row here.
 *
 * <p>Records the actor's IP and user agent because "the owner issued a refund" and "someone with
 * the owner's session issued a refund from an address they have never used" look identical without
 * them.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_merchant", columnList = "merchantId,createdAt"),
        @Index(name = "idx_audit_user", columnList = "userId")
})
public class AuditLog {

    @Id
    private String id;

    private String merchantId;

    /** Null when the actor was an API key rather than a person. */
    private String userId;
    private String userEmail;

    /** Dotted verb: {@code api_key.created}, {@code refund.issued}, {@code member.invited}. */
    private String action;

    private String targetType;
    private String targetId;

    /** Free-form JSON detail: what changed, and to what. */
    @Column(length = 2000)
    private String detail;

    private String ipAddress;
    @Column(length = 500)
    private String userAgent;

    private Instant createdAt = Instant.now();

    protected AuditLog() {
    }

    public AuditLog(String id, String merchantId, String userId, String userEmail, String action,
                    String targetType, String targetId, String detail, String ipAddress,
                    String userAgent) {
        this.id = id;
        this.merchantId = merchantId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetail() {
        return detail;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
