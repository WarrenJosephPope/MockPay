package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.domain.Membership;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.service.AccountService;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.ApiKeyService;
import dev.mockpay.gateway.service.AuditService;
import dev.mockpay.gateway.service.EmailService;
import dev.mockpay.gateway.service.EventService;
import dev.mockpay.gateway.service.IdempotencyService;
import dev.mockpay.gateway.service.LedgerService;
import dev.mockpay.gateway.service.PaymentSearch;
import dev.mockpay.gateway.service.PaymentService;
import dev.mockpay.gateway.service.RefundService;
import dev.mockpay.gateway.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dashboard API — everything a logged-in person can do.
 *
 * <p>Two authorisation checks apply to every call here, and they are different questions:
 *
 * <ol>
 *   <li><b>Tenancy.</b> {@link #actor} resolves the merchant from the <em>session</em>, never from
 *       a request parameter. A merchant id that arrives in a body or a path could be anyone's; one
 *       that came from the session was proved at login. Every downstream query then filters on it.
 *   <li><b>Authority.</b> {@code actor.require(ROLE)} on each mutating endpoint. Read access is
 *       broad; issuing keys and moving money are not.
 * </ol>
 *
 * <p>The role each action needs is a deliberate choice, not a default:
 *
 * <table border="1">
 *   <caption>Required roles</caption>
 *   <tr><th>Action</th><th>Role</th><th>Why</th></tr>
 *   <tr><td>Read anything</td><td>VIEWER</td><td>Support staff need to see payments</td></tr>
 *   <tr><td>Manage webhook endpoints</td><td>DEVELOPER</td><td>Integration work, moves no money</td></tr>
 *   <tr><td>Issue or revoke API keys</td><td>ADMIN</td><td>A key is unlimited authority over the account</td></tr>
 *   <tr><td>Issue a refund</td><td>ADMIN</td><td>Moves money. A developer must not be able to.</td></tr>
 *   <tr><td>Manage the team</td><td>OWNER</td><td>Controls who else has any of the above</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final UserService users;
    private final AccountService accounts;
    private final ApiKeyService apiKeys;
    private final PaymentService payments;
    private final RefundService refunds;
    private final EventService events;
    private final AuditService audit;
    private final EmailService email;
    private final boolean smtpConfigured;
    private final MerchantRepository merchants;
    private final PaymentIntentRepository intents;
    private final PaymentSearch search;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public DashboardController(UserService users, AccountService accounts, ApiKeyService apiKeys,
                               PaymentService payments, RefundService refunds, EventService events,
                               AuditService audit, EmailService email,
                               MerchantRepository merchants, PaymentIntentRepository intents,
                               PaymentSearch search, LedgerService ledger,
                               IdempotencyService idempotency,
                               org.springframework.core.env.Environment environment) {
        this.users = users;
        this.accounts = accounts;
        this.apiKeys = apiKeys;
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.audit = audit;
        this.email = email;
        this.smtpConfigured = !environment.getProperty("spring.mail.host", "").isBlank();
        this.merchants = merchants;
        this.intents = intents;
        this.search = search;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    // ----------------------------------------------------------------- bodies

    public record CreateKeyRequest(String type, String name) {
    }

    public record EndpointRequest(String url, String description, List<String> enabled_events,
                                  Boolean enabled) {
    }

    public record InviteRequest(@NotBlank String email, @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record RefundRequest(@NotBlank String payment_intent, Long amount, String reason) {
    }

    public record SwitchAccountRequest(@NotBlank String merchant_id) {
    }

    public record UpdateAccountRequest(String name, String mcc, String settlement_currency,
                                       String country) {
    }

    // -------------------------------------------------------------------- me

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        var actor = actor(request);
        var merchant = merchants.findById(actor.merchantId()).orElseThrow();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("user", Map.of(
                "id", actor.user().getId(),
                "email", actor.user().getEmail(),
                "name", actor.user().getName() == null ? "" : actor.user().getName()));
        map.put("merchant", Map.of(
                "id", merchant.getId(),
                "name", merchant.getName(),
                "currency", merchant.getSettlementCurrency(),
                "country", merchant.getCountry()));
        map.put("role", actor.role().name());
        map.put("accounts", users.membershipsOf(actor.user().getId()).stream()
                .map(m -> Map.of("merchant_id", m.getMerchantId(), "role", m.getRole().name()))
                .toList());
        return map;
    }

    /** Switch which account the session is acting on, for users who belong to several. */
    @PostMapping("/switch-account")
    public Map<String, Object> switchAccount(@Valid @RequestBody SwitchAccountRequest body,
                                             HttpServletRequest request) {
        String userId = DashboardSession.userId(request);
        // Membership is re-checked here rather than trusted from the request: otherwise anyone
        // could switch into any account by guessing an id.
        users.membership(userId, body.merchant_id())
                .orElseThrow(() -> ApiException.notFound("account"));
        DashboardSession.switchMerchant(request, body.merchant_id());
        return me(request);
    }

    // --------------------------------------------------------------- account

    @GetMapping("/account")
    public Map<String, Object> account(HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.VIEWER);
        return accountSnapshot(merchants.findById(actor.merchantId()).orElseThrow());
    }

    /**
     * Edit the account profile.
     *
     * <p>ADMIN, not OWNER: the name and category code are operational settings, and the things that
     * genuinely cannot be undone — currency and country — are refused outright rather than gated on
     * a role. See {@code AccountService.updateAccount} for why.
     */
    @PatchMapping("/account")
    public Map<String, Object> updateAccount(@RequestBody UpdateAccountRequest body,
                                             HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.ADMIN);

        var merchant = accounts.updateAccount(actor.merchantId(), body.name(), body.mcc(),
                body.settlement_currency(), body.country());
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "account.updated", "merchant", actor.merchantId(),
                Map.of("name", merchant.getName(), "mcc", merchant.getMcc()));
        return accountSnapshot(merchant);
    }

    // -------------------------------------------------------------- api keys

    @GetMapping("/api-keys")
    public Map<String, Object> listKeys(HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);
        return list(apiKeys.list(actor.merchantId()).stream()
                .map(k -> apiKeys.snapshot(k, null)).toList());
    }

    /**
     * Issue a key.
     *
     * <p>Send an {@code Idempotency-Key} header. A dashboard button is clicked twice more often
     * than an API is called twice, and without a key a double click issues two secret keys — one of
     * which the operator never sees again and cannot tell apart from a leak.
     */
    @PostMapping("/api-keys")
    public ResponseEntity<Map<String, Object>> createKey(
            @RequestBody CreateKeyRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        var actor = actor(request);
        // ADMIN, not DEVELOPER: a secret key is unlimited authority over the account, so issuing
        // one is equivalent to granting that authority to whoever receives it.
        actor.require(Membership.Role.ADMIN);

        ApiKey.Type type = "publishable".equalsIgnoreCase(body.type())
                ? ApiKey.Type.PUBLISHABLE : ApiKey.Type.SECRET;

        var outcome = idempotency.execute(actor.merchantId(), idempotencyKey, "POST",
                "/dashboard/api-keys", body, () -> {
                    var issued = apiKeys.issue(actor.merchantId(), type, body.name());
                    audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                            "api_key.created", "api_key", issued.record().getId(),
                            Map.of("type", type, "prefix", issued.record().getKeyPrefix()));
                    return apiKeys.snapshot(issued.record(), issued.plaintext());
                });

        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @PostMapping("/api-keys/{id}/revoke")
    public Map<String, Object> revokeKey(@PathVariable String id, HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.ADMIN);

        var key = apiKeys.revoke(actor.merchantId(), id);
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "api_key.revoked", "api_key", id, Map.of("prefix", key.getKeyPrefix()));
        return apiKeys.snapshot(key, null);
    }

    // ------------------------------------------------------ webhook endpoints

    @GetMapping("/webhook-endpoints")
    public Map<String, Object> listEndpoints(HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);
        return list(accounts.listEndpoints(actor.merchantId()).stream()
                .map(accounts::snapshot).toList());
    }

    @PostMapping("/webhook-endpoints")
    public ResponseEntity<Map<String, Object>> createEndpoint(
            @RequestBody EndpointRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        var actor = actor(request);
        // DEVELOPER is enough: configuring where events are delivered is integration work and
        // moves no money.
        actor.require(Membership.Role.DEVELOPER);

        var outcome = idempotency.execute(actor.merchantId(), idempotencyKey, "POST",
                "/dashboard/webhook-endpoints", body, () -> {
                    var endpoint = accounts.addEndpoint(actor.merchantId(), body.url(),
                            body.description(), joinEvents(body.enabled_events()));
                    audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                            "webhook_endpoint.created", "webhook_endpoint", endpoint.getId(),
                            Map.of("url", endpoint.getUrl()));
                    return accounts.snapshot(endpoint);
                });

        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    /**
     * Send a synthetic event to one endpoint.
     *
     * <p>The first thing anyone wants when a webhook integration is not working: proof the gateway
     * can reach the URL at all, and that the receiver's signature check accepts what it sends.
     */
    @PostMapping("/webhook-endpoints/{id}/test")
    public Map<String, Object> testEndpoint(@PathVariable String id, HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);

        var endpoint = accounts.mustFindEndpoint(actor.merchantId(), id);
        var event = events.sendTestEvent(actor.merchantId(), endpoint);
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "webhook_endpoint.tested", "webhook_endpoint", id, Map.of("event", event.getId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("event_id", event.getId());
        response.put("endpoint_id", endpoint.getId());
        response.put("url", endpoint.getUrl());
        // Queued, not delivered: the dispatcher picks it up within a couple of seconds. Poll
        // /dashboard/events to see how it went.
        response.put("status", "queued");
        return response;
    }

    @PatchMapping("/webhook-endpoints/{id}")
    public Map<String, Object> updateEndpoint(@PathVariable String id,
                                              @RequestBody EndpointRequest body,
                                              HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);

        var endpoint = accounts.updateEndpoint(actor.merchantId(), id, body.url(),
                body.description(), joinEvents(body.enabled_events()), body.enabled());
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "webhook_endpoint.updated", "webhook_endpoint", id,
                Map.of("url", endpoint.getUrl(), "enabled", endpoint.isEnabled()));
        return accounts.snapshot(endpoint);
    }

    @DeleteMapping("/webhook-endpoints/{id}")
    public Map<String, Object> deleteEndpoint(@PathVariable String id, HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);

        accounts.deleteEndpoint(actor.merchantId(), id);
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "webhook_endpoint.deleted", "webhook_endpoint", id, null);
        return Map.of("id", id, "deleted", true);
    }

    // -------------------------------------------------------------- payments

    /**
     * The dashboard's main screen.
     *
     * <p>Every filter is optional and they compose. Dates are ISO calendar days, interpreted in UTC
     * and half-open ({@code from} inclusive, {@code to} exclusive) so that asking for a single day
     * returns that whole day without the caller reasoning about midnight.
     */
    @GetMapping("/payments")
    public Map<String, Object> listPayments(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String created_from,
                                            @RequestParam(required = false) String created_to,
                                            @RequestParam(required = false) Long amount_min,
                                            @RequestParam(required = false) Long amount_max,
                                            @RequestParam(required = false) String last4,
                                            @RequestParam(required = false) String query,
                                            HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.VIEWER);

        var filters = new PaymentSearch.Filters(
                parseStatus(status),
                parseDay(created_from, false),
                parseDay(created_to, true),
                amount_min, amount_max, last4, query);

        var results = search.find(actor.merchantId(), filters,
                PageRequest.of(page, Math.min(limit, 100),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", results.stream().map(payments::snapshot).toList());
        response.put("has_more", results.hasNext());
        response.put("total_count", results.getTotalElements());
        return response;
    }

    @GetMapping("/payments/{id}")
    public Map<String, Object> retrievePayment(@PathVariable String id, HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.VIEWER);

        var intent = payments.mustFind(actor.merchantId(), id);

        Map<String, Object> map = new LinkedHashMap<>(payments.snapshot(intent));
        // The full rail trace, not a count. Reading the 0100 and 0110 for a payment is how you
        // answer "why was this declined" without asking the acquirer.
        map.put("transactions", payments.transactionsFor(actor.merchantId(), id).stream()
                .map(payments::snapshot).toList());
        map.put("refunds", refunds.forPaymentIntent(actor.merchantId(), id).stream()
                .map(refunds::snapshot).toList());
        // And the double-entry journals, so the money can be followed from this one screen.
        map.put("ledger", ledger.forReference(id).stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getId());
            entry.put("journal", e.getJournalId());
            entry.put("account", e.getAccount().name());
            entry.put("direction", e.getDirection().name());
            entry.put("amount", e.getAmount());
            entry.put("currency", e.getCurrency());
            entry.put("memo", e.getMemo());
            return entry;
        }).toList());
        return map;
    }

    /**
     * Issue a refund from the dashboard.
     *
     * <p>The clearest example of why roles exist. A DEVELOPER can configure every integration on
     * the account and still cannot move a penny out of it.
     */
    @PostMapping("/refunds")
    public ResponseEntity<Map<String, Object>> refund(
            @Valid @RequestBody RefundRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.ADMIN);

        // The one place on the dashboard where a duplicate submission costs real money.
        var outcome = idempotency.execute(actor.merchantId(), idempotencyKey, "POST",
                "/dashboard/refunds", body, () -> {
                    var refund = refunds.create(actor.merchantId(), body.payment_intent(),
                            body.amount(), body.reason());
                    audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                            "refund.issued", "refund", refund.getId(),
                            Map.of("payment_intent", body.payment_intent(),
                                    "amount", refund.getAmount()));
                    return refunds.snapshot(refund);
                });

        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    // ---------------------------------------------------------------- events

    @GetMapping("/events")
    public Map<String, Object> listEvents(HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);
        return list(events.listForMerchant(actor.merchantId()).stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", e.getId());
            map.put("type", e.getType());
            map.put("status", e.getStatus().name().toLowerCase());
            map.put("attempts", e.getAttempts());
            map.put("destination", e.getDestinationUrl());
            map.put("last_error", e.getLastError());
            map.put("created", e.getCreatedAt().getEpochSecond());
            return map;
        }).toList());
    }

    @PostMapping("/events/{id}/replay")
    public Map<String, Object> replayEvent(@PathVariable String id, HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.DEVELOPER);

        var event = events.replay(id, actor.merchantId());
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "webhook_event.replayed", "event", id, null);
        return Map.of("id", event.getId(), "status", event.getStatus().name().toLowerCase(),
                "attempts", event.getAttempts());
    }

    // ------------------------------------------------------------------ team

    @GetMapping("/team")
    public Map<String, Object> team(HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.VIEWER);

        List<Map<String, Object>> members = users.teamOf(actor.merchantId()).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("membership_id", m.getId());
            map.put("role", m.getRole().name());
            users.byId(m.getUserId()).ifPresent(u -> {
                map.put("user_id", u.getId());
                map.put("email", u.getEmail());
                map.put("name", u.getName());
            });
            return map;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", members);
        response.put("pending_invitations", users.pendingInvitations(actor.merchantId()).stream()
                .filter(i -> i.getAcceptedAt() == null)
                .map(i -> Map.of("id", i.getId(), "email", i.getEmail(),
                        "role", i.getRole().name(),
                        "expires", i.getExpiresAt().getEpochSecond()))
                .toList());
        return response;
    }

    @PostMapping("/team/invitations")
    public ResponseEntity<Map<String, Object>> invite(@Valid @RequestBody InviteRequest body,
                                                      HttpServletRequest request) {
        var actor = actor(request);
        // OWNER only: inviting someone is granting them authority, and an ADMIN who could invite
        // another OWNER would be able to escalate past their own role.
        actor.require(Membership.Role.OWNER);

        Membership.Role role = parseRole(body.role());
        var issued = users.invite(actor.merchantId(), body.email(), role, actor.user().getId());
        var invitation = issued.invitation();

        var merchant = merchants.findById(actor.merchantId()).orElseThrow();
        email.sendInvitation(invitation.getEmail(), issued.token(), merchant.getName(),
                actor.user().getName() == null ? actor.user().getEmail() : actor.user().getName(),
                role.name());

        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "member.invited", "invitation", invitation.getId(),
                Map.of("email", invitation.getEmail(), "role", role));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", invitation.getId());
        response.put("email", invitation.getEmail());
        response.put("role", role.name());
        response.put("expires", invitation.getExpiresAt().getEpochSecond());
        // The token is echoed back only while no SMTP server is configured. With real mail set up
        // it exists solely in the invitee's inbox — returning it to the inviter would let anyone
        // who can create an invitation also accept it, defeating the point of emailing a link.
        if (!smtpConfigured) {
            response.put("token", issued.token());
            response.put("note", "Token returned because no SMTP host is configured. "
                    + "Set MOCKPAY_SMTP_HOST and it will only be emailed.");
        }
        return ResponseEntity.status(201).body(response);
    }

    @PatchMapping("/team/{membershipId}")
    public Map<String, Object> changeRole(@PathVariable String membershipId,
                                          @Valid @RequestBody RoleRequest body,
                                          HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.OWNER);

        var membership = users.changeRole(actor.merchantId(), membershipId, parseRole(body.role()));
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "member.role_changed", "membership", membershipId,
                Map.of("role", membership.getRole()));
        return Map.of("membership_id", membership.getId(), "role", membership.getRole().name());
    }

    @DeleteMapping("/team/{membershipId}")
    public Map<String, Object> removeMember(@PathVariable String membershipId,
                                            HttpServletRequest request) {
        var actor = actor(request);
        actor.require(Membership.Role.OWNER);

        users.removeMember(actor.merchantId(), membershipId);
        audit.record(actor.merchantId(), actor.user().getId(), actor.user().getEmail(),
                "member.removed", "membership", membershipId, null);
        return Map.of("membership_id", membershipId, "removed", true);
    }

    // ------------------------------------------------------------- audit log

    @GetMapping("/audit-log")
    public Map<String, Object> auditLog(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int limit,
                                        HttpServletRequest request) {
        var actor = actor(request);
        // ADMIN: the audit log shows who did what, which is management information rather than
        // day-to-day integration detail.
        actor.require(Membership.Role.ADMIN);

        var results = audit.list(actor.merchantId(), PageRequest.of(page, Math.min(limit, 200)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", results.stream().map(audit::snapshot).toList());
        response.put("has_more", results.hasNext());
        return response;
    }

    // --------------------------------------------------------------- helpers

    /**
     * Resolve the caller from the session.
     *
     * <p>Both ids come from the session, and the membership is re-read from the database on every
     * request rather than cached at login. That is what makes a revoked membership or a demoted
     * role take effect immediately instead of whenever the user next signs in.
     */
    private DashboardSession.Actor actor(HttpServletRequest request) {
        String userId = DashboardSession.userId(request);
        String merchantId = DashboardSession.merchantId(request);

        var user = users.byId(userId).orElseThrow(() -> new ApiException(401,
                "authentication_error", "not_authenticated", "Sign in to continue."));
        var membership = users.membership(userId, merchantId).orElseThrow(() -> new ApiException(
                403, "permission_error", "no_access", "You no longer have access to this account."));

        return new DashboardSession.Actor(user, membership);
    }

    private PaymentIntent.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PaymentIntent.Status.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_request_error", "invalid_status",
                    "Unknown status '" + raw + "'.");
        }
    }

    /**
     * An ISO calendar day to an instant, in UTC.
     *
     * @param exclusiveEnd when true, returns the start of the <em>next</em> day, so a range of one
     *                     day to itself covers that entire day
     */
    private Instant parseDay(String raw, boolean exclusiveEnd) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(raw.trim());
            return (exclusiveEnd ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            throw new ApiException(400, "invalid_request_error", "invalid_date",
                    "Dates must be ISO calendar days, e.g. 2026-08-14.");
        }
    }

    private Map<String, Object> accountSnapshot(dev.mockpay.gateway.domain.Merchant merchant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", merchant.getId());
        map.put("object", "account");
        map.put("name", merchant.getName());
        map.put("mcc", merchant.getMcc());
        // Returned so the UI can display them, and flagged so it can render them read-only rather
        // than offering an edit that will be refused.
        map.put("settlement_currency", merchant.getSettlementCurrency());
        map.put("country", merchant.getCountry());
        map.put("immutable_fields", List.of("settlement_currency", "country"));
        map.put("created", merchant.getCreatedAt().getEpochSecond());
        return map;
    }

    private Membership.Role parseRole(String raw) {
        try {
            return Membership.Role.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_request_error", "invalid_role",
                    "Role must be one of: OWNER, ADMIN, DEVELOPER, VIEWER.");
        }
    }

    private String joinEvents(List<String> events) {
        return events == null || events.isEmpty() ? null : String.join(",", events);
    }

    private Map<String, Object> list(List<Map<String, Object>> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }
}
