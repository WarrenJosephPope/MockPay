package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.Invitation;
import dev.mockpay.gateway.domain.Membership;
import dev.mockpay.gateway.domain.PasswordResetToken;
import dev.mockpay.gateway.domain.User;
import dev.mockpay.gateway.repo.InvitationRepository;
import dev.mockpay.gateway.repo.MembershipRepository;
import dev.mockpay.gateway.repo.PasswordResetTokenRepository;
import dev.mockpay.gateway.repo.UserRepository;
import dev.mockpay.gateway.support.Crypto;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registration, authentication and team membership for dashboard users.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Failures before an account is locked, and for how long. */
    private static final int MAX_FAILED_ATTEMPTS = 8;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    /** A reset link is a full account takeover in one URL, so its life is measured in minutes. */
    private static final Duration RESET_TOKEN_LIFETIME = Duration.ofHours(1);

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final PasswordResetTokenRepository resetTokens;
    private final AccountService accounts;
    private final PasswordEncoder passwordEncoder;
    private final EmailService email;
    private final SessionRegistry sessions;

    public UserService(UserRepository users, MembershipRepository memberships,
                       InvitationRepository invitations, PasswordResetTokenRepository resetTokens,
                       AccountService accounts, PasswordEncoder passwordEncoder,
                       EmailService email, SessionRegistry sessions) {
        this.users = users;
        this.memberships = memberships;
        this.invitations = invitations;
        this.resetTokens = resetTokens;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.sessions = sessions;
    }

    public record SignupResult(User user, String merchantId, String secretKey, String publishableKey) {
    }

    /**
     * Register a person and create the business they own, in one transaction.
     *
     * <p>Both or neither: a user with no account cannot do anything, and an account with no owner is
     * unreachable. This is the flow that replaces the bootstrap command for humans.
     */
    @Transactional
    public SignupResult signup(String email, String password, String name, String businessName,
                               String currency, String country) {
        String normalised = email == null ? "" : email.toLowerCase().trim();
        validateEmail(normalised);
        validatePassword(password);

        if (users.existsByEmail(normalised)) {
            // Deliberately explicit. Hiding this behind a generic error is sometimes recommended to
            // avoid confirming which addresses are registered, but signup already reveals it: a
            // silent success followed by a login failure is worse for the user and stops nobody,
            // since an attacker can simply try to sign up.
            throw new ApiException(409, "invalid_request_error", "email_already_registered",
                    "An account with that email already exists. Sign in instead.");
        }

        User user = users.save(new User(Ids.generate("usr"), normalised,
                passwordEncoder.encode(password), name));

        var account = accounts.create(
                businessName == null || businessName.isBlank() ? name + "'s business" : businessName,
                currency == null || currency.isBlank() ? "USD" : currency,
                "5399",
                country == null || country.isBlank() ? "US" : country);

        memberships.save(new Membership(Ids.generate("mem"), user.getId(),
                account.merchant().getId(), Membership.Role.OWNER, null));

        log.info("Signed up {} owning {}", normalised, account.merchant().getId());
        return new SignupResult(user, account.merchant().getId(),
                account.secretKey(), account.publishableKey());
    }

    /**
     * Verify credentials.
     *
     * <p>Returns empty for every kind of failure — unknown address, wrong password, locked account —
     * because distinguishing them tells an attacker which addresses are worth attacking.
     *
     * <p>The password is verified even when the account is locked. Skipping the hash comparison
     * would make a locked account respond measurably faster, which leaks exactly the fact the
     * uniform error message is hiding.
     */
    @Transactional
    public Optional<User> authenticate(String email, String password) {
        String normalised = email == null ? "" : email.toLowerCase().trim();
        Optional<User> found = users.findByEmail(normalised);

        if (found.isEmpty()) {
            // Hash anyway against a dummy value, so a missing account takes as long as a real one.
            // Without this, response time alone enumerates registered addresses.
            passwordEncoder.matches(password == null ? "" : password,
                    "$argon2id$v=19$m=16384,t=2,p=1$YWJjZGVmZ2hpamtsbW5vcA$0000000000000000000000000000000000000000000");
            return Optional.empty();
        }

        User user = found.get();
        boolean locked = user.isLocked();
        boolean correct = passwordEncoder.matches(password == null ? "" : password,
                user.getPasswordHash());

        if (!correct) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(Instant.now().plus(LOCKOUT));
                log.warn("Locked {} after {} failed attempts", normalised,
                        user.getFailedLoginAttempts());
            }
            users.save(user);
            return Optional.empty();
        }

        if (locked) {
            log.warn("Correct password for {} but the account is locked until {}",
                    normalised, user.getLockedUntil());
            return Optional.empty();
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return Optional.of(user);
    }

    /**
     * Begin a password reset.
     *
     * <p>Returns nothing and reveals nothing. If the address is not registered the caller gets the
     * same response as if it were — this endpoint must not become a way to discover who has an
     * account.
     */
    @Transactional
    public void requestPasswordReset(String rawEmail, String requestedIp) {
        String normalised = rawEmail == null ? "" : rawEmail.toLowerCase().trim();
        var found = users.findByEmail(normalised);

        if (found.isEmpty()) {
            log.info("Password reset requested for {} - no such account, saying nothing", normalised);
            return;
        }

        User user = found.get();

        // Burn any outstanding tokens. Otherwise requesting a second reset leaves the first link
        // live, doubling the window in which an intercepted email is useful.
        invalidateOutstandingResetTokens(user.getId());

        String token = Ids.random(48);
        resetTokens.save(new PasswordResetToken(Ids.generate("prt"), user.getId(),
                Crypto.sha256Hex(token), Instant.now().plus(RESET_TOKEN_LIFETIME), requestedIp));

        email.sendPasswordReset(user.getEmail(), token);
        log.info("Password reset token issued for {}", user.getEmail());
    }

    /**
     * Complete a password reset.
     *
     * <p>Signing every session out is the part that matters. If an attacker already holds a stolen
     * session, changing the password without killing sessions leaves them logged in - while the
     * user believes they have just locked the intruder out.
     */
    @Transactional
    public User resetPassword(String token, String newPassword) {
        PasswordResetToken record = resetTokens.findByTokenHash(Crypto.sha256Hex(token))
                .orElseThrow(() -> new ApiException(400, "invalid_request_error",
                        "invalid_reset_token", "That reset link is not valid. Request a new one."));

        if (!record.isUsable()) {
            throw new ApiException(400, "invalid_request_error", "invalid_reset_token",
                    "That reset link has expired or was already used. Request a new one.");
        }

        validatePassword(newPassword);
        User user = users.findById(record.getUserId()).orElseThrow();

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // A reset is also the way out of a lockout: the person proved control of the inbox.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        users.save(user);

        record.setUsedAt(Instant.now());
        resetTokens.save(record);
        invalidateOutstandingResetTokens(user.getId());

        sessions.invalidateAllFor(user.getId());
        email.sendPasswordChanged(user.getEmail());

        log.info("Password reset completed for {}; all sessions invalidated", user.getEmail());
        return user;
    }

    private void invalidateOutstandingResetTokens(String userId) {
        Instant now = Instant.now();
        resetTokens.findByUserIdAndUsedAtIsNull(userId).forEach(t -> {
            t.setUsedAt(now);
            resetTokens.save(t);
        });
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(403, "invalid_request_error", "incorrect_password",
                    "The current password is not correct.");
        }
        validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);

        // Same reasoning as a reset: changing the password should end every other session.
        sessions.invalidateAllFor(user.getId());
        email.sendPasswordChanged(user.getEmail());
    }

    // ------------------------------------------------------------ memberships

    public Optional<Membership> membership(String userId, String merchantId) {
        return memberships.findByUserIdAndMerchantId(userId, merchantId);
    }

    public List<Membership> membershipsOf(String userId) {
        return memberships.findByUserId(userId);
    }

    public List<Membership> teamOf(String merchantId) {
        return memberships.findByMerchantId(merchantId);
    }

    public Optional<User> byId(String userId) {
        return users.findById(userId);
    }

    /** The plaintext token, returned once so it can be emailed. Only its hash is stored. */
    public record IssuedInvitation(Invitation invitation, String token) {
    }

    @Transactional
    public IssuedInvitation invite(String merchantId, String rawEmail, Membership.Role role,
                                   String invitedByUserId) {
        String normalised = rawEmail == null ? "" : rawEmail.toLowerCase().trim();
        validateEmail(normalised);

        users.findByEmail(normalised)
                .flatMap(u -> memberships.findByUserIdAndMerchantId(u.getId(), merchantId))
                .ifPresent(m -> {
                    throw new ApiException(409, "invalid_request_error", "already_a_member",
                            "That person is already on this account as " + m.getRole() + ".");
                });

        // Hashed like every other bearer token: a database dump must not yield working links into
        // other people's accounts.
        String token = Ids.random(48);
        Invitation invitation = invitations.save(new Invitation(Ids.generate("inv"), merchantId,
                normalised, role, Crypto.sha256Hex(token), invitedByUserId,
                // Time-boxed. An invitation link that works forever is a permanent backdoor.
                Instant.now().plus(Duration.ofDays(7))));
        return new IssuedInvitation(invitation, token);
    }

    /**
     * Accept an invitation, creating the user if they are new.
     *
     * <p>The invited address is authoritative: whoever holds the token joins as the email that was
     * invited, not one they supply. Otherwise a leaked token would let anyone join under any
     * identity, and the audit trail would name the wrong person.
     */
    @Transactional
    public Membership acceptInvitation(String token, String password, String name) {
        Invitation invitation = invitations.findByToken(Crypto.sha256Hex(token))
                .orElseThrow(() -> ApiException.notFound("invitation"));

        if (!invitation.isUsable()) {
            throw new ApiException(400, "invalid_request_error", "invitation_expired",
                    "This invitation has expired or was already accepted.");
        }

        User user = users.findByEmail(invitation.getEmail()).orElseGet(() -> {
            validatePassword(password);
            return users.save(new User(Ids.generate("usr"), invitation.getEmail(),
                    passwordEncoder.encode(password), name));
        });

        if (memberships.findByUserIdAndMerchantId(user.getId(), invitation.getMerchantId()).isPresent()) {
            throw new ApiException(409, "invalid_request_error", "already_a_member",
                    "You are already on this account.");
        }

        Membership membership = memberships.save(new Membership(Ids.generate("mem"), user.getId(),
                invitation.getMerchantId(), invitation.getRole(), invitation.getInvitedByUserId()));

        invitation.setAcceptedAt(Instant.now());
        invitations.save(invitation);
        return membership;
    }

    @Transactional
    public void removeMember(String merchantId, String membershipId) {
        Membership membership = memberships.findById(membershipId)
                .filter(m -> m.getMerchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("team member"));

        // An account with no owner cannot be administered by anyone, ever. Refuse rather than
        // create an orphan.
        if (membership.getRole() == Membership.Role.OWNER
                && memberships.countByMerchantIdAndRole(merchantId, Membership.Role.OWNER) <= 1) {
            throw new ApiException(400, "invalid_request_error", "cannot_remove_last_owner",
                    "This is the account's only owner. Promote someone else first.");
        }
        memberships.delete(membership);
    }

    @Transactional
    public Membership changeRole(String merchantId, String membershipId, Membership.Role role) {
        Membership membership = memberships.findById(membershipId)
                .filter(m -> m.getMerchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("team member"));

        if (membership.getRole() == Membership.Role.OWNER && role != Membership.Role.OWNER
                && memberships.countByMerchantIdAndRole(merchantId, Membership.Role.OWNER) <= 1) {
            throw new ApiException(400, "invalid_request_error", "cannot_demote_last_owner",
                    "This is the account's only owner. Promote someone else first.");
        }
        membership.setRole(role);
        return memberships.save(membership);
    }

    public List<Invitation> pendingInvitations(String merchantId) {
        return invitations.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    // ------------------------------------------------------------- validation

    private void validateEmail(String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ApiException(400, "invalid_request_error", "invalid_email",
                    "That does not look like an email address.");
        }
    }

    /**
     * Length over composition rules.
     *
     * <p>Mandatory symbols and digits push people towards {@code Password1!} and towards writing it
     * down. Current NIST guidance is to require length, check against known-breached lists, and
     * otherwise leave people alone. The breach check is the part worth adding next.
     */
    private void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new ApiException(400, "invalid_request_error", "weak_password",
                    "Passwords must be at least 12 characters. Length matters more than symbols.");
        }
        if (password.length() > 200) {
            // Argon2 cost scales with input; an unbounded password is a cheap way to burn CPU.
            throw new ApiException(400, "invalid_request_error", "password_too_long",
                    "Passwords must be 200 characters or fewer.");
        }
    }
}
