package dev.mockpay.gateway.api;

import tools.jackson.databind.ObjectMapper;
import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.WebhookEvent;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.service.AccountService;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.ApiKeyService;
import dev.mockpay.gateway.service.DisputeService;
import dev.mockpay.gateway.service.EventService;
import dev.mockpay.gateway.service.IdempotencyService;
import dev.mockpay.gateway.service.LedgerService;
import dev.mockpay.gateway.service.RefundService;
import dev.mockpay.gateway.service.SettlementService;
import dev.mockpay.gateway.service.TokenizationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Payment methods, refunds, disputes, settlements, events and account introspection. */
@RestController
@RequestMapping("/v1")
public class ResourceController {

    private final TokenizationService tokenization;
    private final RefundService refunds;
    private final DisputeService disputes;
    private final SettlementService settlements;
    private final EventService events;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final MerchantRepository merchants;
    private final ApiKeyService apiKeys;
    private final AccountService accounts;
    private final ObjectMapper mapper;

    public ResourceController(TokenizationService tokenization, RefundService refunds,
                              DisputeService disputes, SettlementService settlements,
                              EventService events, LedgerService ledger,
                              IdempotencyService idempotency, MerchantRepository merchants,
                              ApiKeyService apiKeys, AccountService accounts,
                              ObjectMapper mapper) {
        this.tokenization = tokenization;
        this.refunds = refunds;
        this.disputes = disputes;
        this.settlements = settlements;
        this.events = events;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.merchants = merchants;
        this.apiKeys = apiKeys;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------- payment methods

    @PostMapping("/payment_methods")
    public ResponseEntity<Map<String, Object>> createPaymentMethod(
            @Valid @RequestBody Dtos.CreatePaymentMethodRequest body) {
        String merchantId = RequestContext.merchantId();

        PaymentMethod pm = switch (body.type().toLowerCase()) {
            case "card" -> {
                if (body.card() == null) {
                    throw new ApiException(400, "invalid_request_error", "missing_card",
                            "A 'card' object is required when type is 'card'.");
                }
                yield tokenization.tokenizeCard(merchantId, new TokenizationService.CardInput(
                        body.card().number(), body.card().exp_month(),
                        body.card().exp_year(), body.card().cvc()));
            }
            case "upi" -> tokenization.tokenizeUpi(merchantId,
                    body.upi() == null ? null : body.upi().vpa());
            case "wallet" -> tokenization.tokenizeWallet(merchantId,
                    body.wallet() == null ? null : body.wallet().provider());
            default -> throw new ApiException(400, "invalid_request_error", "unsupported_type",
                    "Supported types are: card, upi, wallet.");
        };

        return ResponseEntity.status(201).body(snapshot(pm));
    }

    // ----------------------------------------------------------------- refunds

    @PostMapping("/refunds")
    public ResponseEntity<Map<String, Object>> createRefund(
            @Valid @RequestBody Dtos.CreateRefundRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String merchantId = RequestContext.merchantId();
        var outcome = idempotency.execute(merchantId, idempotencyKey, "POST", "/v1/refunds", body,
                () -> refunds.snapshot(refunds.create(merchantId, body.payment_intent(),
                        body.amount(), body.reason())));
        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @GetMapping("/refunds/{id}")
    public Map<String, Object> retrieveRefund(@PathVariable String id) {
        return refunds.snapshot(refunds.mustFind(RequestContext.merchantId(), id));
    }

    // ---------------------------------------------------------------- disputes

    /**
     * Open a dispute.
     *
     * <p>Only a test affordance. Real chargebacks arrive from the acquirer days or weeks after the
     * sale — there is no API anywhere that lets a merchant charge themselves back.
     */
    @PostMapping("/disputes")
    public ResponseEntity<Map<String, Object>> createDispute(
            @Valid @RequestBody Dtos.CreateDisputeRequest body) {
        return ResponseEntity.status(201).body(disputes.snapshot(
                disputes.open(RequestContext.merchantId(), body.payment_intent(),
                        body.reason_code(), body.amount())));
    }

    @GetMapping("/disputes")
    public Map<String, Object> listDisputes() {
        List<Map<String, Object>> data = disputes.list(RequestContext.merchantId()).stream()
                .map(disputes::snapshot).toList();
        return listResponse(data);
    }

    @GetMapping("/disputes/{id}")
    public Map<String, Object> retrieveDispute(@PathVariable String id) {
        return disputes.snapshot(disputes.mustFind(RequestContext.merchantId(), id));
    }

    @PostMapping("/disputes/{id}/evidence")
    public Map<String, Object> submitEvidence(@PathVariable String id,
                                              @RequestBody Dtos.DisputeEvidenceRequest body) {
        String json;
        try {
            json = mapper.writeValueAsString(body.evidence());
        } catch (Exception e) {
            throw new ApiException(400, "invalid_request_error", "invalid_evidence",
                    "Evidence must be a flat object of string values.");
        }
        return disputes.snapshot(disputes.submitEvidence(RequestContext.merchantId(), id, json));
    }

    @PostMapping("/disputes/{id}/resolve")
    public Map<String, Object> resolveDispute(@PathVariable String id,
                                              @Valid @RequestBody Dtos.ResolveDisputeRequest body) {
        return disputes.snapshot(
                disputes.resolve(RequestContext.merchantId(), id, body.merchant_wins()));
    }

    @PostMapping("/disputes/{id}/accept")
    public Map<String, Object> acceptDispute(@PathVariable String id) {
        return disputes.snapshot(disputes.accept(RequestContext.merchantId(), id));
    }

    @GetMapping("/dispute_reason_codes")
    public Map<String, Object> reasonCodes() {
        List<Map<String, Object>> data = DisputeService.reasonCodes().stream()
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("code", r.code());
                    map.put("description", r.description());
                    map.put("category", r.category());
                    map.put("response_window_days", r.responseDays());
                    return map;
                }).toList();
        return listResponse(data);
    }

    // ------------------------------------------------------------- settlements

    @PostMapping("/settlements/run")
    public ResponseEntity<Map<String, Object>> runSettlement(
            @RequestBody(required = false) Dtos.RunSettlementRequest body) {
        String merchantId = RequestContext.merchantId();
        String currency = body == null || body.currency() == null
                ? merchants.findById(merchantId).orElseThrow().getSettlementCurrency()
                : body.currency();
        LocalDate start = body == null || body.period_start() == null
                ? LocalDate.now().minusDays(1) : LocalDate.parse(body.period_start());
        LocalDate end = body == null || body.period_end() == null
                ? LocalDate.now() : LocalDate.parse(body.period_end());

        return ResponseEntity.status(201).body(settlements.snapshot(
                settlements.runBatch(merchantId, currency, start, end)));
    }

    @PostMapping("/settlements/{id}/payout")
    public Map<String, Object> payout(@PathVariable String id) {
        return settlements.snapshot(settlements.payout(RequestContext.merchantId(), id));
    }

    @GetMapping("/settlements")
    public Map<String, Object> listSettlements() {
        List<Map<String, Object>> data = settlements.list(RequestContext.merchantId()).stream()
                .map(settlements::snapshot).toList();
        return listResponse(data);
    }

    // ------------------------------------------------------------------ events

    @GetMapping("/events")
    public Map<String, Object> listEvents() {
        List<Map<String, Object>> data = events.listForMerchant(RequestContext.merchantId())
                .stream().map(this::snapshot).toList();
        return listResponse(data);
    }

    @PostMapping("/events/{id}/replay")
    public Map<String, Object> replayEvent(@PathVariable String id) {
        return snapshot(events.replay(id, RequestContext.merchantId()));
    }

    // ----------------------------------------------------------------- account

    @GetMapping("/account")
    public Map<String, Object> account() {
        return accountSnapshot(RequestContext.merchant());
    }

    /**
     * Trial balance.
     *
     * <p>{@code _TOTAL_MUST_BE_ZERO} is the assertion, not decoration: if it is ever non-zero, the
     * gateway has invented or destroyed money and nothing downstream can be trusted.
     */
    @GetMapping("/account/balance")
    public Map<String, Object> balance() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("object", "trial_balance");
        map.put("accounts", ledger.trialBalance(RequestContext.merchantId()));
        return map;
    }

    // ---------------------------------------------------------------- api keys

    @GetMapping("/api_keys")
    public Map<String, Object> listKeys() {
        List<Map<String, Object>> data = apiKeys.list(RequestContext.merchantId()).stream()
                // Never any plaintext: secret keys are unrecoverable after creation, by design.
                .map(k -> apiKeys.snapshot(k, null))
                .toList();
        return listResponse(data);
    }

    /**
     * Issue a key.
     *
     * <p>The response is the only time a secret key is readable. Rotation without downtime is what
     * this enables: create the replacement, deploy it, watch {@code last_used_at} on the old one
     * stop moving, then revoke it. A single key column made that impossible.
     */
    @PostMapping("/api_keys")
    public ResponseEntity<Map<String, Object>> createKey(
            @Valid @RequestBody Dtos.CreateApiKeyRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String merchantId = RequestContext.merchantId();
        ApiKey.Type type = "publishable".equalsIgnoreCase(body.type())
                ? ApiKey.Type.PUBLISHABLE : ApiKey.Type.SECRET;

        var outcome = idempotency.execute(merchantId, idempotencyKey, "POST", "/v1/api_keys", body,
                () -> {
                    var issued = apiKeys.issue(merchantId, type, body.name());
                    return apiKeys.snapshot(issued.record(), issued.plaintext());
                });
        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @PostMapping("/api_keys/{id}/revoke")
    public Map<String, Object> revokeKey(@PathVariable String id) {
        return apiKeys.snapshot(apiKeys.revoke(RequestContext.merchantId(), id), null);
    }

    // ------------------------------------------------------- webhook endpoints

    @GetMapping("/webhook_endpoints")
    public Map<String, Object> listEndpoints() {
        List<Map<String, Object>> data = accounts.listEndpoints(RequestContext.merchantId())
                .stream().map(accounts::snapshot).toList();
        return listResponse(data);
    }

    @PostMapping("/webhook_endpoints")
    public ResponseEntity<Map<String, Object>> createEndpoint(
            @Valid @RequestBody Dtos.CreateWebhookEndpointRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String merchantId = RequestContext.merchantId();
        var outcome = idempotency.execute(merchantId, idempotencyKey, "POST",
                "/v1/webhook_endpoints", body,
                () -> accounts.snapshot(accounts.addEndpoint(merchantId, body.url(),
                        body.description(), joinEvents(body.enabled_events()))));
        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @GetMapping("/webhook_endpoints/{id}")
    public Map<String, Object> retrieveEndpoint(@PathVariable String id) {
        return accounts.snapshot(accounts.mustFindEndpoint(RequestContext.merchantId(), id));
    }

    @PatchMapping("/webhook_endpoints/{id}")
    public Map<String, Object> updateEndpoint(@PathVariable String id,
                                              @RequestBody Dtos.UpdateWebhookEndpointRequest body) {
        return accounts.snapshot(accounts.updateEndpoint(RequestContext.merchantId(), id,
                body.url(), body.description(), joinEvents(body.enabled_events()), body.enabled()));
    }

    @DeleteMapping("/webhook_endpoints/{id}")
    public Map<String, Object> deleteEndpoint(@PathVariable String id) {
        accounts.deleteEndpoint(RequestContext.merchantId(), id);
        return Map.of("id", id, "object", "webhook_endpoint", "deleted", true);
    }

    /**
     * Backwards-compatible shortcut: replace the account's endpoints with a single URL.
     *
     * <p>Kept because the README, the docs and existing integrations all use it. It is a
     * convenience over the endpoint collection, not a separate concept.
     */
    @PostMapping("/account/webhook")
    public Map<String, Object> setWebhook(@Valid @RequestBody Dtos.UpdateWebhookRequest body) {
        String merchantId = RequestContext.merchantId();
        accounts.listEndpoints(merchantId)
                .forEach(e -> accounts.deleteEndpoint(merchantId, e.getId()));
        accounts.addEndpoint(merchantId, body.url(), "Set via /v1/account/webhook", null);
        return accountSnapshot(RequestContext.merchant());
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> accountSnapshot(Merchant merchant) {
        var endpointList = accounts.listEndpoints(merchant.getId());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", merchant.getId());
        map.put("object", "account");
        map.put("name", merchant.getName());
        // Returned in full: a publishable key is public by design.
        map.put("publishable_key", apiKeys.publishableKeyFor(merchant.getId()).orElse(null));
        map.put("settlement_currency", merchant.getSettlementCurrency());
        map.put("mcc", merchant.getMcc());
        map.put("country", merchant.getCountry());
        // Mirrors of the first endpoint, retained so the single-webhook API and the existing docs
        // keep working. The endpoint collection below is the real model.
        map.put("webhook_url", endpointList.isEmpty() ? null : endpointList.get(0).getUrl());
        map.put("webhook_secret", endpointList.isEmpty() ? null : endpointList.get(0).getSecret());
        map.put("webhook_endpoints", endpointList.stream().map(accounts::snapshot).toList());
        return map;
    }

    /** Null means "all events". An explicit empty list would mean "none", which is never intended. */
    private String joinEvents(List<String> events) {
        return events == null || events.isEmpty() ? null : String.join(",", events);
    }

    private Map<String, Object> snapshot(PaymentMethod pm) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pm.getId());
        map.put("object", "payment_method");
        map.put("type", pm.getType().name().toLowerCase());
        map.put("created", pm.getCreatedAt().getEpochSecond());

        switch (pm.getType()) {
            case CARD -> {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("brand", pm.getCardBrand());
                card.put("last4", pm.getCardLast4());
                card.put("exp_month", pm.getCardExpMonth());
                card.put("exp_year", pm.getCardExpYear());
                card.put("funding", pm.getCardFunding());
                card.put("issuer", pm.getCardIssuer());
                card.put("country", pm.getCardCountry());
                card.put("bin", pm.getCardBin());
                card.put("fingerprint", pm.getCardFingerprint());
                // Shown here only to make the concept visible. A real API returns at most the
                // last four of the network token, never the token itself.
                card.put("network_token_last4", pm.getNetworkToken() == null ? null
                        : pm.getNetworkToken().substring(pm.getNetworkToken().length() - 4));
                map.put("card", card);
            }
            case UPI -> map.put("upi", Map.of("vpa", pm.getUpiVpa()));
            case WALLET, NETBANKING -> map.put("wallet", Map.of("provider", pm.getWalletProvider()));
        }
        return map;
    }

    private Map<String, Object> snapshot(WebhookEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.getId());
        map.put("object", "event");
        map.put("type", event.getType());
        map.put("status", event.getStatus().name().toLowerCase());
        map.put("attempts", event.getAttempts());
        map.put("destination", event.getDestinationUrl());
        map.put("last_response_status", event.getLastResponseStatus());
        map.put("last_error", event.getLastError());
        map.put("next_attempt_at", event.getNextAttemptAt() == null ? null
                : event.getNextAttemptAt().getEpochSecond());
        map.put("created", event.getCreatedAt().getEpochSecond());
        try {
            map.put("payload", mapper.readValue(event.getPayloadJson(), Map.class));
        } catch (Exception ignored) {
            map.put("payload", event.getPayloadJson());
        }
        return map;
    }

    private Map<String, Object> listResponse(List<Map<String, Object>> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }
}
