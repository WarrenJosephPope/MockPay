package dev.mockpay.gateway.service;

import tools.jackson.databind.ObjectMapper;
import dev.mockpay.gateway.domain.WebhookEndpoint;
import dev.mockpay.gateway.domain.WebhookEvent;
import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.repo.WebhookEndpointRepository;
import dev.mockpay.gateway.repo.WebhookEventRepository;
import dev.mockpay.gateway.support.Crypto;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound events: the transactional outbox, plus the dispatcher that drains it.
 *
 * <p>Webhooks are how a merchant learns about anything that happens without them asking — a UPI
 * collect approved four minutes after the browser gave up, a chargeback filed three weeks after the
 * sale, a payout landing. Any merchant integration that relies on the browser redirect to know
 * whether a payment succeeded is broken, because customers close tabs.
 *
 * <p>Delivery here is <b>at-least-once</b>, which is the strongest guarantee anyone can offer over
 * a network: the alternative to retrying an ambiguous send is not delivering it at all. Consumers
 * therefore have to deduplicate on event id. Every real gateway documents this, and most integration
 * bugs come from merchants who did not read that sentence.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final WebhookEventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final GatewayProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EventService(WebhookEventRepository events, WebhookEndpointRepository endpoints,
                        GatewayProperties props, ObjectMapper mapper) {
        this.events = events;
        this.endpoints = endpoints;
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getWebhook().getTimeoutMs()))
                // Never auto-follow redirects to a merchant-supplied URL: it is an easy pivot into
                // internal networks and a classic SSRF vector.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Write the event inside the caller's transaction.
     *
     * <p>{@code MANDATORY} is the load-bearing annotation. It makes it a startup-visible error to
     * emit an event outside a transaction, which is precisely the mistake that produces events for
     * state changes that later rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WebhookEvent> emit(String merchantId, String type, Map<String, Object> data) {
        List<WebhookEndpoint> subscribed = endpoints.findByMerchantIdAndEnabledTrue(merchantId)
                .stream()
                .filter(e -> e.subscribesTo(type))
                .toList();

        if (subscribed.isEmpty()) {
            // Not an error. A merchant with no endpoints configured still gets a queryable event
            // log via GET /v1/events; they simply are not being pushed to.
            log.debug("No enabled endpoint subscribes to {} for {}", type, merchantId);
            return List.of();
        }

        List<WebhookEvent> created = new ArrayList<>(subscribed.size());
        for (WebhookEndpoint endpoint : subscribed) {
            // A distinct event id per endpoint. Consumers deduplicate on it, so two endpoints
            // sharing one id would make a legitimate second delivery look like a replay.
            String eventId = Ids.generate("evt");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", eventId);
            envelope.put("object", "event");
            envelope.put("type", type);
            envelope.put("created", Instant.now().getEpochSecond());
            envelope.put("livemode", false);
            envelope.put("data", Map.of("object", data));

            try {
                String payload = mapper.writeValueAsString(envelope);
                created.add(events.save(new WebhookEvent(eventId, merchantId, type, payload,
                        endpoint.getUrl(), endpoint.getId())));
            } catch (Exception e) {
                throw new IllegalStateException("Could not serialise event payload", e);
            }
        }
        return created;
    }

    /**
     * Send a synthetic event to one endpoint, to prove it is reachable.
     *
     * <p>Two deliberate differences from a real event. It goes to <b>one</b> endpoint rather than
     * fanning out, because the question being answered is "does this endpoint work", not "does
     * delivery work". And it ignores that endpoint's event-type filter — a test that silently
     * delivered nothing because {@code endpoint.test} was not in the subscription list would tell
     * the operator exactly the wrong thing.
     */
    @Transactional
    public WebhookEvent sendTestEvent(String merchantId, WebhookEndpoint endpoint) {
        String eventId = Ids.generate("evt");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", "endpoint_test");
        data.put("endpoint_id", endpoint.getId());
        data.put("message", "If you are reading this, your endpoint is reachable and your "
                + "signature check accepted it.");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("object", "event");
        envelope.put("type", "endpoint.test");
        envelope.put("created", Instant.now().getEpochSecond());
        envelope.put("livemode", false);
        envelope.put("data", Map.of("object", data));

        try {
            WebhookEvent event = events.save(new WebhookEvent(eventId, merchantId, "endpoint.test",
                    mapper.writeValueAsString(envelope), endpoint.getUrl(), endpoint.getId()));
            log.info("Queued test event {} for endpoint {}", eventId, endpoint.getId());
            return event;
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise test event", e);
        }
    }

    /**
     * The dispatcher.
     *
     * <p>Single-threaded and polling, which is honest for a teaching implementation. At real volume
     * you would claim rows with {@code SELECT ... FOR UPDATE SKIP LOCKED} so several dispatchers can
     * drain the same table without stepping on each other, and shard by merchant so one slow
     * endpoint cannot starve everyone else's events — head-of-line blocking is the characteristic
     * failure of a naive webhook queue.
     */
    @Scheduled(fixedDelay = 2000)
    public void dispatchDue() {
        List<WebhookEvent> due = events.findByStatusInAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                List.of(WebhookEvent.Status.PENDING, WebhookEvent.Status.RETRYING), Instant.now());
        for (WebhookEvent event : due) {
            try {
                attemptDelivery(event);
            } catch (Exception e) {
                log.warn("Dispatcher error for {}: {}", event.getId(), e.toString());
            }
        }
    }

    @Transactional
    public void attemptDelivery(WebhookEvent event) {
        WebhookEvent managed = events.findById(event.getId()).orElse(null);
        if (managed == null || managed.getStatus() == WebhookEvent.Status.DELIVERED) {
            return;
        }

        managed.setAttempts(managed.getAttempts() + 1);

        if (managed.getDestinationUrl() == null || managed.getDestinationUrl().isBlank()) {
            // No endpoint configured. Not an error — the event log is still queryable by API.
            managed.setStatus(WebhookEvent.Status.DEAD);
            managed.setLastError("No webhook URL configured for this merchant");
            events.save(managed);
            return;
        }

        WebhookEndpoint endpoint = managed.getEndpointId() == null ? null
                : endpoints.findById(managed.getEndpointId()).orElse(null);
        if (endpoint == null) {
            managed.setStatus(WebhookEvent.Status.DEAD);
            managed.setLastError("Endpoint no longer exists");
            events.save(managed);
            return;
        }

        long timestamp = Instant.now().getEpochSecond();

        // Signed with THIS endpoint's secret, not an account-wide one. Rotating one endpoint's
        // secret must not break the others, and a compromised staging endpoint must not be able to
        // forge events to production.
        //
        // Sign timestamp AND body together. Signing only the body would let anyone who ever
        // captured one valid request replay it forever.
        String signedPayload = timestamp + "." + managed.getPayloadJson();
        String signature = Crypto.hmacSha256Hex(endpoint.getSecret(), signedPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(managed.getDestinationUrl()))
                .timeout(Duration.ofMillis(props.getWebhook().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "MockPay-Webhooks/1.0")
                .header("MockPay-Event-Id", managed.getId())
                .header("MockPay-Event-Type", managed.getType())
                .header("MockPay-Delivery-Attempt", String.valueOf(managed.getAttempts()))
                .header("MockPay-Signature", "t=" + timestamp + ",v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(managed.getPayloadJson()))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            managed.setLastResponseStatus(response.statusCode());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                managed.setStatus(WebhookEvent.Status.DELIVERED);
                managed.setDeliveredAt(Instant.now());
                managed.setLastError(null);
                log.info("Delivered {} ({}) to {} on attempt {}", managed.getId(), managed.getType(),
                        managed.getDestinationUrl(), managed.getAttempts());
            } else {
                scheduleRetry(managed, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            // A timeout is genuinely ambiguous: the merchant may have processed it and been slow to
            // answer. We retry regardless, which is why their handler must be idempotent.
            scheduleRetry(managed, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        events.save(managed);
    }

    /**
     * Exponential backoff.
     *
     * <p>Retrying a struggling endpoint every second is how you turn its bad minute into a bad hour.
     * Backing off gives it room to recover, and spreading the schedule over hours means an endpoint
     * that was down for a deploy still receives everything once it returns.
     */
    private void scheduleRetry(WebhookEvent event, String error) {
        event.setLastError(truncate(error, 900));
        List<Long> schedule = props.getWebhook().getBackoffSeconds();
        int attempt = event.getAttempts();

        if (attempt >= props.getWebhook().getMaxAttempts() || attempt > schedule.size()) {
            event.setStatus(WebhookEvent.Status.DEAD);
            log.warn("Event {} exhausted its retry budget after {} attempts: {}",
                    event.getId(), attempt, error);
            return;
        }

        long delaySeconds = schedule.get(Math.min(attempt - 1, schedule.size() - 1));
        // Jitter, so a thousand events queued during an outage do not all retry on the same tick.
        long jitter = (long) (delaySeconds * 0.2 * Math.random());
        event.setStatus(WebhookEvent.Status.RETRYING);
        event.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds + jitter));
        log.info("Event {} attempt {} failed ({}); retrying in {}s",
                event.getId(), attempt, error, delaySeconds + jitter);
    }

    /** Manual replay, for when a merchant fixed their endpoint after events went to the dead letter. */
    @Transactional
    public WebhookEvent replay(String eventId, String merchantId) {
        WebhookEvent event = events.findByIdAndMerchantId(eventId, merchantId).orElseThrow(
                () -> new IllegalArgumentException("No such event: " + eventId));
        event.setStatus(WebhookEvent.Status.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now());
        event.setLastError(null);
        // Pick up the endpoint's current URL, since the reason for replaying is usually that it
        // was wrong or unreachable at the time.
        if (event.getEndpointId() != null) {
            endpoints.findById(event.getEndpointId())
                    .ifPresent(e -> event.setDestinationUrl(e.getUrl()));
        }
        return events.save(event);
    }

    public List<WebhookEvent> listForMerchant(String merchantId) {
        return events.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return null;
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
