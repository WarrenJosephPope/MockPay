package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.repo.ApiKeyRepository;
import dev.mockpay.gateway.support.Crypto;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues, verifies and revokes API keys.
 *
 * <p>The rule that shapes this class: <b>a secret key exists in readable form exactly once</b>, in
 * the response to the call that created it. After that only its SHA-256 survives. There is no
 * "show me my key again" endpoint, because there cannot be one — that is the entire point.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** How much of the key is kept in the clear, purely so a human can identify it in a list. */
    private static final int DISPLAY_PREFIX_LENGTH = 16;

    private final ApiKeyRepository keys;

    public ApiKeyService(ApiKeyRepository keys) {
        this.keys = keys;
    }

    /** A freshly minted key. {@code plaintext} is the only time the secret is ever readable. */
    public record IssuedKey(ApiKey record, String plaintext) {
    }

    /**
     * Mint a key.
     *
     * <p>32 characters from a CSPRNG is roughly 190 bits of entropy — far beyond brute force, which
     * is what makes a fast hash the correct choice for storage.
     */
    @Transactional
    public IssuedKey issue(String merchantId, ApiKey.Type type, String name) {
        String plaintext = (type == ApiKey.Type.SECRET ? "sk_test_" : "pk_test_") + Ids.random(32);
        return persist(merchantId, type, name, plaintext);
    }

    /**
     * Store a key whose value is already decided.
     *
     * <p>Exists so the seeded demo accounts can keep their documented keys
     * ({@code sk_test_demo_us_secret}) while still being stored as hashes like everything else. The
     * README stays accurate and the storage model stays honest.
     */
    @Transactional
    public IssuedKey issueWithValue(String merchantId, ApiKey.Type type, String name, String plaintext) {
        return persist(merchantId, type, name, plaintext);
    }

    private IssuedKey persist(String merchantId, ApiKey.Type type, String name, String plaintext) {
        ApiKey key = new ApiKey(
                Ids.generate("key"),
                merchantId,
                type,
                Crypto.sha256Hex(plaintext),
                plaintext.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, plaintext.length())),
                // Publishable keys are public by design, so the plaintext is retained and can always
                // be displayed. Secret keys get null here, forever.
                type == ApiKey.Type.PUBLISHABLE ? plaintext : null,
                name);
        keys.save(key);
        log.info("Issued {} key {} ({}) for {}", type, key.getId(), key.getKeyPrefix(), merchantId);
        return new IssuedKey(key, plaintext);
    }

    /**
     * Resolve a presented key.
     *
     * <p>One indexed lookup on the hash. Revoked keys are found but rejected, which lets the caller
     * distinguish "never existed" from "was revoked" if it ever wants to.
     */
    public Optional<ApiKey> resolve(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        return keys.findByKeyHash(Crypto.sha256Hex(presented)).filter(ApiKey::isActive);
    }

    /**
     * Record that a key was used.
     *
     * <p>Deliberately coarse — only written when the stored value is more than an hour stale. An
     * exact timestamp would mean a write on every single authenticated request, turning a read path
     * into a write path and putting a row lock in front of every API call.
     */
    @Transactional
    public void touch(ApiKey key) {
        Instant last = key.getLastUsedAt();
        if (last == null || last.isBefore(Instant.now().minusSeconds(3600))) {
            key.setLastUsedAt(Instant.now());
            keys.save(key);
        }
    }

    @Transactional
    public ApiKey revoke(String merchantId, String keyId) {
        ApiKey key = keys.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> ApiException.notFound("API key"));

        if (!key.isActive()) {
            throw new ApiException(400, "invalid_request_error", "key_already_revoked",
                    "That key was already revoked on " + key.getRevokedAt() + ".");
        }

        // Refuse to strand the account. Revoking the last live secret key would leave the merchant
        // unable to authenticate at all, including to create a replacement.
        if (key.getType() == ApiKey.Type.SECRET
                && keys.findByMerchantIdAndTypeAndRevokedAtIsNull(merchantId, ApiKey.Type.SECRET).size() <= 1) {
            throw new ApiException(400, "invalid_request_error", "cannot_revoke_last_key",
                    "This is the only active secret key on the account. Create its replacement "
                            + "first, then revoke this one.");
        }

        key.setRevokedAt(Instant.now());
        keys.save(key);
        log.info("Revoked key {} ({}) for {}", key.getId(), key.getKeyPrefix(), merchantId);
        return key;
    }

    public List<ApiKey> list(String merchantId) {
        return keys.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /** The account's publishable key — safe to hand out, so returned in full. */
    public Optional<String> publishableKeyFor(String merchantId) {
        return keys.findByMerchantIdAndTypeAndRevokedAtIsNull(merchantId, ApiKey.Type.PUBLISHABLE)
                .stream()
                .findFirst()
                .map(ApiKey::getPublicValue);
    }

    /**
     * @param plaintext non-null only on the response to a creation call
     */
    public Map<String, Object> snapshot(ApiKey key, String plaintext) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", key.getId());
        map.put("object", "api_key");
        map.put("type", key.getType().name().toLowerCase());
        map.put("name", key.getName());
        map.put("prefix", key.getKeyPrefix());
        map.put("livemode", false);
        map.put("last_used_at", key.getLastUsedAt() == null ? null : key.getLastUsedAt().getEpochSecond());
        map.put("revoked_at", key.getRevokedAt() == null ? null : key.getRevokedAt().getEpochSecond());
        map.put("created", key.getCreatedAt().getEpochSecond());

        if (key.getType() == ApiKey.Type.PUBLISHABLE) {
            map.put("key", key.getPublicValue());
        } else if (plaintext != null) {
            map.put("key", plaintext);
            map.put("warning", "Store this now. It cannot be retrieved again.");
        }
        return map;
    }
}
