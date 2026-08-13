package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Which people can act on which merchant account, and with what authority.
 *
 * <p>The join between {@link User} and {@link Merchant}. Roles live here rather than on the user
 * because authority is per account: the same person can own one business and be a read-only
 * observer on another.
 */
@Entity
@Table(name = "memberships",
        uniqueConstraints = @UniqueConstraint(name = "uk_membership", columnNames = {"userId", "merchantId"}),
        indexes = {
                @Index(name = "idx_membership_user", columnList = "userId"),
                @Index(name = "idx_membership_merchant", columnList = "merchantId")
        })
public class Membership {

    /**
     * Ordered from most to least authority. {@link #atLeast} relies on that order, so the
     * declaration sequence is load-bearing — reordering these silently changes who can do what.
     */
    public enum Role {
        /** Everything, including deleting the account and removing other owners. */
        OWNER,
        /** Manage the team, keys and endpoints; issue refunds. Cannot delete the account. */
        ADMIN,
        /** Keys and endpoints, replay webhooks. Explicitly <b>cannot move money</b>. */
        DEVELOPER,
        /** Read-only. */
        VIEWER;

        /**
         * Whether this role carries at least the authority of {@code required}.
         *
         * <p>A hierarchy rather than a permission matrix. That is the right trade for four roles —
         * a matrix would be more flexible and much easier to get subtly wrong. If the model ever
         * needs "developer but also refunds", that is the point to switch.
         */
        public boolean atLeast(Role required) {
            return this.ordinal() <= required.ordinal();
        }
    }

    @Id
    private String id;

    private String userId;
    private String merchantId;

    @Enumerated(EnumType.STRING)
    private Role role;

    /** Who granted this. Part of the answer to "how did they get access?". */
    private String invitedBy;

    private Instant createdAt = Instant.now();

    protected Membership() {
    }

    public Membership(String id, String userId, String merchantId, Role role, String invitedBy) {
        this.id = id;
        this.userId = userId;
        this.merchantId = merchantId;
        this.role = role;
        this.invitedBy = invitedBy;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
