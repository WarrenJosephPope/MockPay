package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.Membership;
import dev.mockpay.gateway.domain.User;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.AuditService;
import dev.mockpay.gateway.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signup, login, logout.
 *
 * <p>These are the only {@code /dashboard} endpoints reachable without a session, which is why they
 * are the ones that need the most care: this is where credential stuffing arrives.
 */
@RestController
@RequestMapping("/dashboard/auth")
public class DashboardAuthController {

    private final UserService users;
    private final MerchantRepository merchants;
    private final AuditService audit;

    public DashboardAuthController(UserService users, MerchantRepository merchants,
                                   AuditService audit) {
        this.users = users;
        this.merchants = merchants;
        this.audit = audit;
    }

    public record SignupRequest(@NotBlank String email, @NotBlank String password, String name,
                                String business_name, String currency, String country) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AcceptInvitationRequest(@NotBlank String token, String password, String name) {
    }

    /**
     * Register a person and create the business they own.
     *
     * <p>The API keys are returned here, once. This is the human equivalent of the bootstrap
     * command, and it is what makes an empty deployment usable without one.
     */
    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest body,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        var result = users.signup(body.email(), body.password(), body.name(),
                body.business_name(), body.currency(), body.country());

        DashboardSession.establish(request, response, result.user().getId(), result.merchantId());
        audit.record(result.merchantId(), result.user().getId(), result.user().getEmail(),
                "account.created", "merchant", result.merchantId(),
                Map.of("via", "signup"));

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("user", userSnapshot(result.user()));
        body2.put("merchant_id", result.merchantId());
        body2.put("secret_key", result.secretKey());
        body2.put("publishable_key", result.publishableKey());
        body2.put("warning", "Store the secret key now. It cannot be retrieved again.");
        return ResponseEntity.status(201).body(body2);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        User user = users.authenticate(body.email(), body.password())
                // One message for every failure mode — unknown address, wrong password, locked
                // account. Distinguishing them tells an attacker which addresses are worth
                // attacking, and which ones they have already made progress against.
                .orElseThrow(() -> new ApiException(401, "authentication_error",
                        "invalid_credentials", "Email or password is incorrect."));

        List<Membership> memberships = users.membershipsOf(user.getId());
        if (memberships.isEmpty()) {
            throw new ApiException(403, "permission_error", "no_accounts",
                    "This user is not a member of any account.");
        }

        Membership active = memberships.get(0);
        DashboardSession.establish(request, response, user.getId(), active.getMerchantId());
        audit.record(active.getMerchantId(), user.getId(), user.getEmail(),
                "session.login", "user", user.getId(), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", userSnapshot(user));
        result.put("merchant_id", active.getMerchantId());
        result.put("role", active.getRole().name());
        result.put("accounts", memberships.stream().map(this::membershipSnapshot).toList());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        if (DashboardSession.isAuthenticated(request)) {
            String userId = DashboardSession.userId(request);
            String merchantId = DashboardSession.merchantId(request);
            users.byId(userId).ifPresent(u -> audit.record(merchantId, userId, u.getEmail(),
                    "session.logout", "user", userId, null));
        }
        // Invalidating the session removes the row from SPRING_SESSION. Unlike a JWT, the
        // credential genuinely stops working the moment this returns.
        DashboardSession.destroy(request);
        return Map.of("logged_out", true);
    }

    @PostMapping("/accept-invitation")
    public Map<String, Object> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest body,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        Membership membership = users.acceptInvitation(body.token(), body.password(), body.name());
        User user = users.byId(membership.getUserId()).orElseThrow();

        DashboardSession.establish(request, response, user.getId(), membership.getMerchantId());
        audit.record(membership.getMerchantId(), user.getId(), user.getEmail(),
                "member.joined", "membership", membership.getId(),
                Map.of("role", membership.getRole()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", userSnapshot(user));
        result.put("merchant_id", membership.getMerchantId());
        result.put("role", membership.getRole().name());
        return result;
    }

    public record ForgotPasswordRequest(@NotBlank String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {
    }

    /**
     * Request a password reset link.
     *
     * <p><b>Always 200, always the same body</b>, whether or not the address is registered. Any
     * difference — a different status, a different message, even a measurably different response
     * time — turns this endpoint into a way to test whether someone has an account here, which is
     * exactly the list an attacker wants before starting a credential-stuffing run.
     *
     * <p>It follows that the caller cannot be told the mail failed to send either. That is the
     * correct trade: the alternative leaks more than it helps.
     */
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body,
                                              HttpServletRequest request) {
        users.requestPasswordReset(body.email(), clientIp(request));
        return Map.of("message",
                "If an account exists for that address, a reset link has been sent.");
    }

    /**
     * Set a new password using a reset token.
     *
     * <p>Succeeding here signs the user out everywhere — see {@code UserService.resetPassword}.
     * Anyone holding a stolen session loses it at this moment, which is the whole reason a user
     * resets their password after a scare.
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest body,
                                             HttpServletRequest request) {
        User user = users.resetPassword(body.token(), body.password());
        audit.record(null, user.getId(), user.getEmail(), "password.reset", "user", user.getId(),
                Map.of("ip", clientIp(request)));

        // Deliberately no session established. Making them log in with the new password confirms
        // it was stored as they expect, and means a reset link left in browser history does not
        // hand over a live session.
        DashboardSession.destroy(request);
        return Map.of("message", "Password updated. Sign in with your new password.");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    /** Cheap probe for the SPA to decide whether to render the app or the login screen. */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        return Map.of("authenticated", DashboardSession.isAuthenticated(request));
    }

    private Map<String, Object> userSnapshot(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("object", "user");
        map.put("email", user.getEmail());
        map.put("name", user.getName());
        map.put("created", user.getCreatedAt().getEpochSecond());
        // No password hash, no MFA secret, no lockout state. Nothing here that would help an
        // attacker who has stolen a session.
        return map;
    }

    private Map<String, Object> membershipSnapshot(Membership membership) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("merchant_id", membership.getMerchantId());
        map.put("role", membership.getRole().name());
        merchants.findById(membership.getMerchantId())
                .ifPresent(m -> map.put("name", m.getName()));
        return map;
    }
}
