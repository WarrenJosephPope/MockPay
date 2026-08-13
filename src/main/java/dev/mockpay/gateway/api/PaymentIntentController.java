package dev.mockpay.gateway.api;

import tools.jackson.databind.ObjectMapper;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.Transaction;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.IdempotencyService;
import dev.mockpay.gateway.service.LedgerService;
import dev.mockpay.gateway.service.PaymentService;
import dev.mockpay.gateway.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The payments API. */
@RestController
@RequestMapping("/v1/payment_intents")
public class PaymentIntentController {

    private final PaymentService payments;
    private final RefundService refunds;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final PaymentIntentRepository intents;
    private final ObjectMapper mapper;

    public PaymentIntentController(PaymentService payments, RefundService refunds,
                                   LedgerService ledger, IdempotencyService idempotency,
                                   PaymentIntentRepository intents, ObjectMapper mapper) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.intents = intents;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody Dtos.CreatePaymentIntentRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        String merchantId = RequestContext.merchantId();

        IdempotencyService.Outcome outcome = idempotency.execute(
                merchantId, idempotencyKey, "POST", "/v1/payment_intents", body, () -> {
                    PaymentIntent.CaptureMethod captureMethod =
                            "manual".equalsIgnoreCase(body.capture_method())
                                    ? PaymentIntent.CaptureMethod.MANUAL
                                    : PaymentIntent.CaptureMethod.AUTOMATIC;

                    PaymentIntent intent = payments.create(merchantId, body.amount(),
                            body.currency(), captureMethod, body.description(), body.customer(),
                            body.statement_descriptor(), writeMetadata(body.metadata()));

                    // create-and-confirm in one call: convenient for server-side flows where the
                    // instrument is already known, and the only way to make a payment atomic with
                    // respect to a single idempotency key.
                    if (Boolean.TRUE.equals(body.confirm()) && body.payment_method() != null) {
                        intent = payments.confirm(merchantId, intent.getId(), body.payment_method(),
                                body.return_url(), request.getRemoteAddr(),
                                request.getHeader("User-Agent"));
                    }
                    return payments.snapshot(intent);
                });

        return ResponseEntity.status(outcome.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @GetMapping("/{id}")
    public Map<String, Object> retrieve(@PathVariable String id) {
        return payments.snapshot(payments.mustFind(RequestContext.merchantId(), id));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String status) {
        String merchantId = RequestContext.merchantId();
        var pageable = PageRequest.of(page, Math.min(limit, 100));

        var results = status == null
                ? intents.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : intents.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                        merchantId, parseStatus(status), pageable);

        List<Map<String, Object>> data = new ArrayList<>();
        results.forEach(intent -> data.add(payments.snapshot(intent)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        response.put("has_more", results.hasNext());
        response.put("total_count", results.getTotalElements());
        return response;
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id,
                                       @RequestBody(required = false) Dtos.ConfirmPaymentIntentRequest body,
                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                       String idempotencyKey,
                                       HttpServletRequest request) {
        String merchantId = RequestContext.merchantId();
        String paymentMethodId = body == null ? null : body.payment_method();

        if (paymentMethodId == null) {
            PaymentIntent existing = payments.mustFind(merchantId, id);
            paymentMethodId = existing.getPaymentMethodId();
        }
        if (paymentMethodId == null) {
            throw new ApiException(400, "invalid_request_error", "missing_payment_method",
                    "Attach a payment method before confirming.");
        }

        final String pmId = paymentMethodId;
        return idempotency.execute(merchantId, idempotencyKey, "POST",
                "/v1/payment_intents/" + id + "/confirm", body,
                () -> payments.snapshot(payments.confirm(merchantId, id, pmId,
                        body == null ? null : body.return_url(),
                        request.getRemoteAddr(), request.getHeader("User-Agent")))).body();
    }

    @PostMapping("/{id}/capture")
    public Map<String, Object> capture(@PathVariable String id,
                                       @RequestBody(required = false) Dtos.CapturePaymentIntentRequest body,
                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                       String idempotencyKey) {
        String merchantId = RequestContext.merchantId();
        Long amount = body == null ? null : body.amount_to_capture();
        return idempotency.execute(merchantId, idempotencyKey, "POST",
                "/v1/payment_intents/" + id + "/capture", body,
                () -> payments.snapshot(payments.capture(merchantId, id, amount))).body();
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
                                      @RequestBody(required = false) Dtos.CancelPaymentIntentRequest body) {
        String reason = body == null ? "requested_by_customer" : body.cancellation_reason();
        return payments.snapshot(payments.cancel(RequestContext.merchantId(), id, reason));
    }

    /**
     * The rail trace for one payment.
     *
     * <p>No production gateway exposes this to merchants — it is what their support engineers see
     * internally. It is here because reading the actual 0100 and 0110 messages for a payment you
     * just made teaches more about card processing than any diagram.
     */
    @GetMapping("/{id}/transactions")
    public Map<String, Object> transactions(@PathVariable String id) {
        List<Transaction> txns = payments.transactionsFor(RequestContext.merchantId(), id);
        List<Map<String, Object>> data = txns.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", t.getId());
            map.put("type", t.getType().name().toLowerCase());
            map.put("outcome", t.getOutcome() == null ? null : t.getOutcome().name().toLowerCase());
            map.put("amount", t.getAmount());
            map.put("currency", t.getCurrency());
            map.put("mti", t.getMti());
            map.put("response_code", t.getResponseCode());
            map.put("response_text", t.getResponseText());
            map.put("auth_code", t.getAuthCode());
            map.put("rrn", t.getRrn());
            map.put("rail", t.getRailName());
            map.put("acquirer", t.getAcquirerId());
            map.put("latency_ms", t.getLatencyMs());
            map.put("request", t.getRequestDump());
            map.put("response", t.getResponseDump());
            map.put("created", t.getCreatedAt().getEpochSecond());
            return map;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("payment_intent", id);
        response.put("data", data);
        return response;
    }

    /** The double-entry journals this payment produced. */
    @GetMapping("/{id}/ledger")
    public Map<String, Object> ledgerEntries(@PathVariable String id) {
        payments.mustFind(RequestContext.merchantId(), id);
        List<Map<String, Object>> data = ledger.forReference(id).stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", e.getId());
            map.put("journal", e.getJournalId());
            map.put("account", e.getAccount().name());
            map.put("direction", e.getDirection().name());
            map.put("amount", e.getAmount());
            map.put("currency", e.getCurrency());
            map.put("memo", e.getMemo());
            return map;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }

    @GetMapping("/{id}/refunds")
    public Map<String, Object> refundsFor(@PathVariable String id) {
        List<Map<String, Object>> data = refunds
                .forPaymentIntent(RequestContext.merchantId(), id).stream()
                .map(refunds::snapshot)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }

    private PaymentIntent.Status parseStatus(String raw) {
        try {
            return PaymentIntent.Status.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_request_error", "invalid_status",
                    "Unknown status '" + raw + "'.");
        }
    }

    private String writeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }
}
