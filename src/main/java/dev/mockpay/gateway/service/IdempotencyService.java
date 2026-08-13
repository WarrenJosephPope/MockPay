package dev.mockpay.gateway.service;

import tools.jackson.databind.ObjectMapper;
import dev.mockpay.gateway.domain.IdempotencyRecord;
import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.repo.IdempotencyRecordRepository;
import dev.mockpay.gateway.support.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes retrying a payment request safe.
 *
 * <p>The problem is not hypothetical. A client sends "charge this card £40", the network drops the
 * response, and the client has no way to distinguish "it never arrived" from "it worked and the
 * reply was lost". Retrying risks charging twice; not retrying risks not charging at all. An
 * idempotency key removes the dilemma: the server recognises the repeat and replays the original
 * outcome.
 *
 * <p>The subtlety is what happens <em>during</em> the first attempt. Two copies of the same request
 * can be in flight simultaneously, so the key must be claimed before any work begins, and the
 * second caller must be told to wait rather than allowed to proceed. That claim is done here with a
 * unique-constraint insert in its own transaction — the database is the only thing in the system
 * that can arbitrate this correctly.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final GatewayProperties props;
    private final ObjectMapper mapper;

    /**
     * Used explicitly rather than relying on {@code @Transactional} on private methods. Spring's
     * annotation works through a proxy, so a self-call would silently run in the caller's
     * transaction — and "silently runs in the wrong transaction" is exactly the bug this class
     * exists to prevent.
     */
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRecordRepository records, GatewayProperties props,
                              ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.records = records;
        this.props = props;
        this.mapper = mapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Either the freshly computed result, or the replayed one from the original attempt. */
    public record Outcome(Map<String, Object> body, boolean replayed) {
    }

    /**
     * Run {@code operation} at most once per (merchant, key) pair.
     *
     * <p>When no key is supplied the operation simply runs — the API stays usable for reads and for
     * clients that have not adopted keys, and the guarantee is opt-in exactly as it is in production
     * gateways.
     */
    public Outcome execute(String merchantId, String idempotencyKey, String method, String path,
                           Object requestBody, Supplier<Map<String, Object>> operation) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Outcome(operation.get(), false);
        }
        if (idempotencyKey.length() > 255) {
            throw new ApiException(400, "idempotency_error", "idempotency_key_too_long",
                    "Idempotency keys must be 255 characters or fewer.");
        }

        String recordId = merchantId + ":" + idempotencyKey;
        String fingerprint = fingerprint(method, path, requestBody);

        Optional<IdempotencyRecord> existing = records.findById(recordId);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), fingerprint);
        }

        // Claim the key. If a concurrent request beat us here the insert fails, and that failure is
        // the answer: someone else owns this operation.
        try {
            claim(recordId, merchantId, idempotencyKey, fingerprint);
        } catch (DataIntegrityViolationException e) {
            IdempotencyRecord raced = records.findById(recordId).orElseThrow(
                    () -> new ApiException(409, "idempotency_error", "idempotency_conflict",
                            "A concurrent request is using this idempotency key. Retry shortly."));
            return replayOrReject(raced, fingerprint);
        }

        Map<String, Object> result;
        try {
            result = operation.get();
        } catch (RuntimeException e) {
            // Release the key so a corrected retry is possible. Holding it after a failure would
            // mean the client could never retry the operation at all — worse than a double charge,
            // because they cannot even find out what happened.
            releaseFailedClaim(recordId);
            throw e;
        }

        complete(recordId, result);
        return new Outcome(result, false);
    }

    /**
     * Insert the claim in its own transaction, so the row survives whether or not the surrounding
     * operation later rolls back.
     */
    private void claim(String recordId, String merchantId, String key, String fingerprint) {
        requiresNew.executeWithoutResult(status ->
                records.saveAndFlush(new IdempotencyRecord(recordId, merchantId, key, fingerprint)));
    }

    private void complete(String recordId, Map<String, Object> result) {
        requiresNew.executeWithoutResult(status -> records.findById(recordId).ifPresent(record -> {
            try {
                record.setState(IdempotencyRecord.State.COMPLETED);
                record.setResponseStatus(200);
                record.setResponseBody(mapper.writeValueAsString(result));
                record.setResourceId(String.valueOf(result.get("id")));
                record.setCompletedAt(Instant.now());
                records.save(record);
            } catch (Exception e) {
                log.warn("Could not store idempotent response for {}: {}", recordId, e.toString());
            }
        }));
    }

    private void releaseFailedClaim(String recordId) {
        requiresNew.executeWithoutResult(status -> records.deleteById(recordId));
    }

    private Outcome replayOrReject(IdempotencyRecord record, String fingerprint) {
        if (!Crypto.constantTimeEquals(record.getRequestFingerprint(), fingerprint)) {
            // The same key with different parameters is a client bug. Returning the first response
            // would quietly give them the wrong object; failing loudly is the kinder answer.
            throw new ApiException(422, "idempotency_error", "idempotency_key_reused",
                    "This idempotency key was already used with different request parameters. "
                            + "Generate a new key for a different request.");
        }

        if (record.getState() == IdempotencyRecord.State.IN_PROGRESS) {
            throw new ApiException(409, "idempotency_error", "idempotency_in_progress",
                    "The original request with this key is still being processed. Retry in a moment.");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(record.getResponseBody(), Map.class);
            log.info("Replaying stored response for idempotency key {}", record.getIdempotencyKey());
            return new Outcome(body, true);
        } catch (Exception e) {
            throw new ApiException(500, "api_error", "idempotency_replay_failed",
                    "The stored response for this key could not be read.");
        }
    }

    private String fingerprint(String method, String path, Object body) {
        try {
            String canonical = method + " " + path + " "
                    + (body == null ? "" : mapper.writeValueAsString(body));
            return Crypto.sha256Hex(canonical);
        } catch (Exception e) {
            return Crypto.sha256Hex(method + " " + path);
        }
    }

    /**
     * Keys are kept for a bounded window — 24 hours is the industry norm.
     *
     * <p>Keeping them forever would grow without limit; expiring them too fast would let a client
     * retrying after a long outage double-charge a customer.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(props.getIdempotency().getRetentionHours()));
        var expired = records.findByCreatedAtBefore(cutoff);
        if (!expired.isEmpty()) {
            records.deleteAll(expired);
            log.info("Purged {} expired idempotency keys", expired.size());
        }
    }
}
