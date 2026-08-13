package dev.mockpay.gateway.api;

import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.support.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A worked example of the <em>receiving</em> side of a webhook.
 *
 * <p>This is not gateway functionality — it is the code a merchant has to write, included because
 * getting it wrong is the most common integration mistake in payments. It demonstrates the three
 * things every handler must do:
 *
 * <ol>
 *   <li><b>Verify the signature</b> over the raw body. An unverified webhook endpoint is a public
 *       URL that anyone can POST "payment succeeded" to, and merchants have shipped real goods to
 *       people who found one.
 *   <li><b>Reject stale timestamps.</b> Without this, a single captured request can be replayed
 *       forever, and a valid signature is no defence because the signature is still valid.
 *   <li><b>Deduplicate on event id.</b> Delivery is at-least-once, so the same event will arrive
 *       twice sooner or later. Fulfilling an order twice is a real cost.
 * </ol>
 */
@RestController
@RequestMapping("/webhook-sink")
public class WebhookSinkController {

    private static final Logger log = LoggerFactory.getLogger(WebhookSinkController.class);

    /** Anything older than this is refused, however well signed. */
    private static final long TOLERANCE_SECONDS = 300;
    private static final int MAX_RETAINED = 200;

    private final MerchantRepository merchants;
    private final List<Map<String, Object>> received =
            Collections.synchronizedList(new LinkedList<>());
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public WebhookSinkController(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "MockPay-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "MockPay-Event-Id", required = false) String eventId,
            @RequestHeader(value = "MockPay-Event-Type", required = false) String eventType,
            @RequestHeader(value = "MockPay-Delivery-Attempt", required = false) String attempt,
            // The RAW body, not a parsed object. Re-serialising JSON reorders keys and changes
            // whitespace, and the signature is over bytes — this is the single most common reason
            // signature verification "mysteriously" fails.
            @RequestBody String rawBody) {

        if (signatureHeader == null) {
            return ResponseEntity.status(400).body(Map.of("error", "missing signature header"));
        }

        String timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                if ("t".equals(kv[0].trim())) {
                    timestamp = kv[1].trim();
                } else if ("v1".equals(kv[0].trim())) {
                    signature = kv[1].trim();
                }
            }
        }

        if (timestamp == null || signature == null) {
            return ResponseEntity.status(400).body(Map.of("error", "malformed signature header"));
        }

        long age = Instant.now().getEpochSecond() - Long.parseLong(timestamp);
        if (Math.abs(age) > TOLERANCE_SECONDS) {
            log.warn("Rejecting webhook {} — timestamp is {}s old", eventId, age);
            return ResponseEntity.status(400).body(Map.of("error", "timestamp outside tolerance"));
        }

        // Try every configured secret, because this one sink stands in for several merchants.
        boolean verified = merchants.findAll().stream().anyMatch(m -> Crypto.constantTimeEquals(
                Crypto.hmacSha256Hex(m.getWebhookSecret(), timestampAndBody(signatureHeader, rawBody)),
                extractSignature(signatureHeader)));

        if (!verified) {
            log.warn("Rejecting webhook {} — signature did not verify", eventId);
            return ResponseEntity.status(401).body(Map.of("error", "signature verification failed"));
        }

        // Idempotent processing. Acknowledge the duplicate with 200 so the gateway stops retrying,
        // but do not act on it twice.
        boolean isNew = eventId == null || processedEventIds.add(eventId);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event_id", eventId);
        entry.put("type", eventType);
        entry.put("attempt", attempt);
        entry.put("duplicate", !isNew);
        entry.put("received_at", Instant.now().toString());
        entry.put("body", rawBody);

        synchronized (received) {
            received.add(0, entry);
            while (received.size() > MAX_RETAINED) {
                received.remove(received.size() - 1);
            }
        }

        log.info("Webhook received: {} {} (attempt {}{})", eventId, eventType, attempt,
                isNew ? "" : ", DUPLICATE — ignored");

        return ResponseEntity.ok(Map.of("received", true, "duplicate", !isNew));
    }

    @GetMapping("/received")
    public Map<String, Object> list() {
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (received) {
            response.put("count", received.size());
            response.put("events", List.copyOf(received));
        }
        return response;
    }

    @DeleteMapping("/received")
    public Map<String, Object> clear() {
        received.clear();
        processedEventIds.clear();
        return Map.of("cleared", true);
    }

    private String timestampAndBody(String signatureHeader, String rawBody) {
        String timestamp = signatureHeader.split(",")[0].replace("t=", "").trim();
        return timestamp + "." + rawBody;
    }

    private String extractSignature(String signatureHeader) {
        for (String part : signatureHeader.split(",")) {
            if (part.trim().startsWith("v1=")) {
                return part.trim().substring(3);
            }
        }
        return "";
    }
}
