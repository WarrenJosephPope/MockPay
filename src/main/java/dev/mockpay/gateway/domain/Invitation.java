package dev.mockpay.gateway.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A pending invitation to join a merchant account.
 *
 * <p>Exists as its own row rather than creating the {@link Membership} immediately because the
 * invitee may not have an account yet, and because an invitation that is never accepted must expire
 * rather than lingering as silent access.
 *
 * <p>The token is the credential. It is random, single-use, and time-boxed — an invitation link that
 * works forever is a permanent backdoor into someone else's payment account.
 */
@Entity
@Table(name = "invitations", indexes = {
        @Index(name = "idx_invitation_token", columnList = "token", unique = true),
        @Index(name = "idx_invitation_merchant", columnList = "merchantId")
})
public class Invitation {

    @Id
    private String id;

    private String merchantId;
    private String email;

    @Enumerated(EnumType.STRING)
    private Membership.Role role;

    private String token;
    private String invitedByUserId;

    private Instant expiresAt;
    private Instant acceptedAt;
    private Instant createdAt = Instant.now();

    protected Invitation() {
    }

    public Invitation(String id, String merchantId, String email, Membership.Role role,
                      String token, String invitedByUserId, Instant expiresAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.email = email.toLowerCase().trim();
        this.role = role;
        this.token = token;
        this.invitedByUserId = invitedByUserId;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return acceptedAt == null && Instant.now().isBefore(expiresAt);
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getEmail() {
        return email;
    }

    public Membership.Role getRole() {
        return role;
    }

    public String getToken() {
        return token;
    }

    public String getInvitedByUserId() {
        return invitedByUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
