package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.WebhookEndpoint;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.repo.WebhookEndpointRepository;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creating and configuring merchant accounts.
 *
 * <p>Account creation lives here rather than in a controller because it has to be atomic: a merchant
 * with no keys cannot authenticate, so the account and its first key pair are created together or
 * not at all. Both the seeder and the bootstrap command go through this one path.
 */
@Service
public class AccountService {

    private final MerchantRepository merchants;
    private final WebhookEndpointRepository endpoints;
    private final ApiKeyService apiKeys;

    public AccountService(MerchantRepository merchants, WebhookEndpointRepository endpoints,
                          ApiKeyService apiKeys) {
        this.merchants = merchants;
        this.endpoints = endpoints;
        this.apiKeys = apiKeys;
    }

    /** A newly created account, with its secret key readable for the only time. */
    public record NewAccount(Merchant merchant, ApiKey.Type ignored, String secretKey,
                             String publishableKey) {
    }

    /**
     * Create an account with a generated key pair.
     *
     * @return the secret key in plaintext — the caller must show it to the operator immediately,
     *         because only its hash is stored
     */
    @Transactional
    public NewAccount create(String name, String currency, String mcc, String country) {
        Merchant merchant = merchants.save(new Merchant(
                Ids.generate("acct"), name, currency.toUpperCase(), mcc, country.toUpperCase()));

        var secret = apiKeys.issue(merchant.getId(), ApiKey.Type.SECRET, "Default secret key");
        var publishable = apiKeys.issue(merchant.getId(), ApiKey.Type.PUBLISHABLE,
                "Default publishable key");

        return new NewAccount(merchant, null, secret.plaintext(), publishable.plaintext());
    }

    /**
     * Create an account with predetermined ids and key values.
     *
     * <p>Only for seeding the documented demo accounts, so the README's
     * {@code sk_test_demo_us_secret} keeps working while still being stored as a hash.
     */
    @Transactional
    public Merchant createWithFixedKeys(String id, String name, String currency, String mcc,
                                        String country, String secretKey, String publishableKey) {
        Merchant merchant = merchants.save(new Merchant(id, name, currency, mcc, country));
        apiKeys.issueWithValue(id, ApiKey.Type.SECRET, "Default secret key", secretKey);
        apiKeys.issueWithValue(id, ApiKey.Type.PUBLISHABLE, "Default publishable key", publishableKey);
        return merchant;
    }

    /**
     * Update the parts of an account that are safe to change.
     *
     * <p>Name and merchant category code are editable. <b>Settlement currency and country are
     * not</b>, and refusing them is the interesting part: every existing payment, ledger entry and
     * settlement on the account is denominated in that currency. Changing it retroactively would
     * not convert anything — it would silently relabel a book of GBP as USD and misstate every
     * balance. Country is locked for a related reason: it drives acquirer routing, and existing
     * authorisations were routed on the old value.
     *
     * <p>Real gateways handle this by making you open a second account.
     */
    @Transactional
    public Merchant updateAccount(String merchantId, String name, String mcc,
                                  String attemptedCurrency, String attemptedCountry) {
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> ApiException.notFound("account"));

        if (attemptedCurrency != null
                && !attemptedCurrency.equalsIgnoreCase(merchant.getSettlementCurrency())) {
            throw new ApiException(400, "invalid_request_error", "currency_immutable",
                    "Settlement currency cannot be changed: existing payments, ledger entries and "
                            + "settlements are denominated in " + merchant.getSettlementCurrency()
                            + ", and relabelling them would misstate every balance. "
                            + "Open a separate account instead.");
        }
        if (attemptedCountry != null && !attemptedCountry.equalsIgnoreCase(merchant.getCountry())) {
            throw new ApiException(400, "invalid_request_error", "country_immutable",
                    "Account country cannot be changed: it determines acquirer routing, and "
                            + "existing authorisations were routed on the current value.");
        }

        if (name != null && !name.isBlank()) {
            merchant.setName(name.trim());
        }
        if (mcc != null && !mcc.isBlank()) {
            if (!mcc.matches("\\d{4}")) {
                throw new ApiException(400, "invalid_request_error", "invalid_mcc",
                        "A merchant category code is four digits (ISO 18245).");
            }
            merchant.setMcc(mcc);
        }
        return merchants.save(merchant);
    }

    @Transactional
    public WebhookEndpoint addEndpoint(String merchantId, String url, String description,
                                       String eventTypes) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new ApiException(400, "invalid_request_error", "invalid_webhook_url",
                    "A webhook URL must be an absolute http:// or https:// address.");
        }
        return endpoints.save(new WebhookEndpoint(
                Ids.generate("whe"), merchantId, url,
                // Per-endpoint secret, generated here and shown in full: the merchant needs it to
                // verify signatures, and unlike an API key it is useless without also being able
                // to receive the requests.
                "whsec_" + Ids.random(32),
                description, eventTypes));
    }

    @Transactional
    public WebhookEndpoint updateEndpoint(String merchantId, String endpointId, String url,
                                          String description, String eventTypes, Boolean enabled) {
        WebhookEndpoint endpoint = mustFindEndpoint(merchantId, endpointId);
        if (url != null) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new ApiException(400, "invalid_request_error", "invalid_webhook_url",
                        "A webhook URL must be an absolute http:// or https:// address.");
            }
            endpoint.setUrl(url);
        }
        if (description != null) {
            endpoint.setDescription(description);
        }
        if (eventTypes != null) {
            endpoint.setEventTypes(eventTypes.isBlank() ? null : eventTypes);
        }
        if (enabled != null) {
            endpoint.setEnabled(enabled);
            if (enabled) {
                // Re-enabling clears the failure count, so an endpoint that was auto-disabled after
                // an outage gets a clean slate rather than tripping again immediately.
                endpoint.setConsecutiveFailures(0);
            }
        }
        return endpoints.save(endpoint);
    }

    @Transactional
    public void deleteEndpoint(String merchantId, String endpointId) {
        endpoints.delete(mustFindEndpoint(merchantId, endpointId));
    }

    public WebhookEndpoint mustFindEndpoint(String merchantId, String endpointId) {
        return endpoints.findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> ApiException.notFound("webhook endpoint"));
    }

    public List<WebhookEndpoint> listEndpoints(String merchantId) {
        return endpoints.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public Map<String, Object> snapshot(WebhookEndpoint endpoint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", endpoint.getId());
        map.put("object", "webhook_endpoint");
        map.put("url", endpoint.getUrl());
        map.put("description", endpoint.getDescription());
        map.put("enabled", endpoint.isEnabled());
        map.put("enabled_events", endpoint.getEventTypes() == null
                ? List.of("*") : List.copyOf(endpoint.subscribedTypes()));
        map.put("secret", endpoint.getSecret());
        map.put("consecutive_failures", endpoint.getConsecutiveFailures());
        map.put("created", endpoint.getCreatedAt().getEpochSecond());
        return map;
    }
}
