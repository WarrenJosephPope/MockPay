package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.ApiKeyService;
import dev.mockpay.gateway.service.PaymentService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for the demo storefront's own backend.
 *
 * <p>The demo checkout page used to hold {@code sk_test_demo_us_secret} in its JavaScript. That is
 * the one thing in this project that modelled bad practice: a secret key in page source lets any
 * visitor refund, capture and read every payment on the account. It was a shortcut taken because
 * the page had no server of its own.
 *
 * <p>This is that server. It is the smallest honest version of what a real merchant runs — it holds
 * the secret key, creates the PaymentIntent, and hands the browser only a client secret, which
 * authorises acting on that one payment and nothing else. The checkout page now knows no secrets.
 *
 * <p><b>Demo only.</b> Gated on the same flag as the seeded accounts, because it operates on one of
 * them without authenticating anybody. With {@code MOCKPAY_SEED_DEMO_ACCOUNTS=false} these
 * endpoints do not exist — which is exactly right, since a real deployment must not have an
 * unauthenticated endpoint that creates payments.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final String DEMO_MERCHANT = "acct_demo_us";

    private final PaymentService payments;
    private final ApiKeyService apiKeys;
    private final boolean enabled;

    public DemoController(PaymentService payments, ApiKeyService apiKeys,
                          @Value("${mockpay.seed-demo-accounts:true}") boolean enabled) {
        this.payments = payments;
        this.apiKeys = apiKeys;
        this.enabled = enabled;
    }

    public record CheckoutSessionRequest(@NotNull @Positive Long amount, String currency,
                                         String description, String capture_method) {
    }

    /**
     * What a storefront's backend does: create the intent, return the client secret.
     *
     * <p>Note what is <em>not</em> returned — no secret key, no account detail, nothing about other
     * payments. The browser receives exactly enough to complete this one payment.
     */
    @PostMapping("/checkout-session")
    public Map<String, Object> createSession(@RequestBody CheckoutSessionRequest body) {
        requireEnabled();

        PaymentIntent intent = payments.create(
                DEMO_MERCHANT,
                body.amount(),
                body.currency() == null || body.currency().isBlank() ? "USD" : body.currency(),
                "manual".equalsIgnoreCase(body.capture_method())
                        ? PaymentIntent.CaptureMethod.MANUAL
                        : PaymentIntent.CaptureMethod.AUTOMATIC,
                body.description() == null ? "Demo order" : body.description(),
                null, "DEMO STORE", null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payment_intent_id", intent.getId());
        response.put("client_secret", intent.getClientSecret());
        // Safe to hand out: a publishable key can tokenise and nothing else.
        response.put("publishable_key", apiKeys.publishableKeyFor(DEMO_MERCHANT).orElse(null));
        response.put("amount", intent.getAmount());
        response.put("currency", intent.getCurrency());
        return response;
    }

    /**
     * Read back the outcome, the way a storefront would when rendering its confirmation page.
     *
     * <p>Scoped to the demo account, so it cannot be used to read anybody else's payments.
     */
    @GetMapping("/payments/{id}")
    public Map<String, Object> retrieve(@PathVariable String id) {
        requireEnabled();
        return payments.snapshot(payments.mustFind(DEMO_MERCHANT, id));
    }

    /**
     * The rail trace, which is the most instructive thing the demo can show.
     *
     * <p>A real storefront would never expose this — it is internal support detail — but reading
     * the 0100 and 0110 for a payment you just made is the whole point of the demo.
     */
    @GetMapping("/payments/{id}/transactions")
    public Map<String, Object> trace(@PathVariable String id) {
        requireEnabled();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", payments.transactionsFor(DEMO_MERCHANT, id).stream()
                .map(payments::snapshot).toList());
        return response;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(404, "invalid_request_error", "demo_disabled",
                    "The demo storefront is disabled because demo accounts are not seeded.");
        }
    }
}
