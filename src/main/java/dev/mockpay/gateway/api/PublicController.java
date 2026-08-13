package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.PendingAction;
import dev.mockpay.gateway.rails.TestInstruments;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.repo.PendingActionRepository;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.service.ApiException;
import dev.mockpay.gateway.service.ApiKeyService;
import dev.mockpay.gateway.service.PaymentService;
import dev.mockpay.gateway.service.TokenizationService;
import dev.mockpay.gateway.support.Crypto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browser-facing API. No secret key ever reaches this surface.
 *
 * <p>Two credentials operate here, and neither can do damage on its own. The <b>publishable key</b>
 * says which account a tokenisation request belongs to — it can create tokens, which is harmless,
 * because a token is only usable by the merchant that owns it. The <b>client secret</b> is bound to
 * one PaymentIntent and authorises acting on that intent alone.
 *
 * <p>This split is what makes it acceptable for card details to be typed into a page the merchant
 * controls: the number goes straight from the browser to the gateway, the merchant's server never
 * sees it, and the worst an attacker can do with the keys embedded in the page is pay the merchant.
 */
@RestController
@RequestMapping("/v1/public")
// The whole point is to be callable from a merchant's own origin. Production restricts this to
// registered domains rather than allowing everything.
@CrossOrigin(origins = "*")
public class PublicController {

    private final TokenizationService tokenization;
    private final PaymentService payments;
    private final PaymentIntentRepository intents;
    private final MerchantRepository merchants;
    private final PendingActionRepository pendingActions;
    private final ApiKeyService apiKeys;

    public PublicController(TokenizationService tokenization, PaymentService payments,
                            PaymentIntentRepository intents, MerchantRepository merchants,
                            PendingActionRepository pendingActions, ApiKeyService apiKeys) {
        this.tokenization = tokenization;
        this.payments = payments;
        this.intents = intents;
        this.merchants = merchants;
        this.pendingActions = pendingActions;
        this.apiKeys = apiKeys;
    }

    /** Tokenise a card straight from the browser. This is the call that keeps merchants out of scope. */
    @PostMapping("/payment_methods")
    public Map<String, Object> tokenize(@RequestParam("key") String publishableKey,
                                        @RequestBody Dtos.CreatePaymentMethodRequest body) {
        // Resolved through the same hashed lookup as secret keys, then checked to be the
        // publishable kind — so a leaked secret key cannot be used here either.
        var key = apiKeys.resolve(publishableKey)
                .filter(k -> k.getType() == dev.mockpay.gateway.domain.ApiKey.Type.PUBLISHABLE)
                .orElseThrow(() -> new ApiException(401, "authentication_error", "invalid_api_key",
                        "Unrecognised publishable key."));
        Merchant merchant = merchants.findById(key.getMerchantId())
                .orElseThrow(() -> new ApiException(401, "authentication_error", "invalid_api_key",
                        "Unrecognised publishable key."));

        PaymentMethod pm = switch (body.type().toLowerCase()) {
            case "card" -> tokenization.tokenizeCard(merchant.getId(),
                    new TokenizationService.CardInput(body.card().number(), body.card().exp_month(),
                            body.card().exp_year(), body.card().cvc()));
            case "upi" -> tokenization.tokenizeUpi(merchant.getId(), body.upi().vpa());
            case "wallet" -> tokenization.tokenizeWallet(merchant.getId(), body.wallet().provider());
            default -> throw new ApiException(400, "invalid_request_error", "unsupported_type",
                    "Supported types are: card, upi, wallet.");
        };

        // Deliberately thin. The browser has no business knowing the fingerprint or the BIN.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", pm.getId());
        response.put("type", pm.getType().name().toLowerCase());
        if (pm.getType() == PaymentMethod.Type.CARD) {
            response.put("card", Map.of("brand", pm.getCardBrand(), "last4", pm.getCardLast4()));
        }
        return response;
    }

    @GetMapping("/payment_intents/{id}")
    public Map<String, Object> retrieve(@PathVariable String id,
                                        @RequestParam("client_secret") String clientSecret) {
        return publicSnapshot(authorize(id, clientSecret));
    }

    /** Confirm from the browser, using the client secret rather than a key. */
    @PostMapping("/payment_intents/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id,
                                       @RequestParam("client_secret") String clientSecret,
                                       @RequestBody Dtos.ConfirmPaymentIntentRequest body,
                                       HttpServletRequest request) {
        PaymentIntent intent = authorize(id, clientSecret);
        PaymentIntent confirmed = payments.confirm(intent.getMerchantId(), id, body.payment_method(),
                body.return_url(), request.getRemoteAddr(), request.getHeader("User-Agent"));
        return publicSnapshot(confirmed);
    }

    // ---------------------------------------------- simulated issuer / PSP apps

    /** Stands in for the issuer's ACS challenge page submitting an OTP. */
    @PostMapping("/challenge/3ds/{actionId}")
    public Map<String, Object> submitOtp(@PathVariable String actionId,
                                         @RequestBody Map<String, String> body) {
        PaymentIntent intent = payments.completeThreeDsChallenge(actionId, body.get("otp"));
        return publicSnapshot(intent);
    }

    /** Stands in for the payer's UPI app approving or rejecting a collect request. */
    @PostMapping("/challenge/upi/{actionId}")
    public Map<String, Object> resolveUpi(@PathVariable String actionId,
                                          @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        return publicSnapshot(payments.resolveUpiCollect(actionId, approve));
    }

    /** Stands in for the wallet's own approval screen. */
    @PostMapping("/challenge/wallet/{actionId}")
    public Map<String, Object> resolveWallet(@PathVariable String actionId,
                                             @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        return publicSnapshot(payments.resolveWalletRedirect(actionId, approve));
    }

    /** Context for the simulated challenge pages to render themselves. */
    @GetMapping("/challenge/{actionId}")
    public Map<String, Object> challengeContext(@PathVariable String actionId) {
        PendingAction action = pendingActions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("pending action"));
        PaymentIntent intent = intents.findById(action.getPaymentIntentId()).orElseThrow();
        Merchant merchant = merchants.findById(action.getMerchantId()).orElseThrow();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action_id", action.getId());
        map.put("kind", action.getKind().name().toLowerCase());
        map.put("payment_intent", intent.getId());
        map.put("amount", intent.getAmount());
        map.put("currency", intent.getCurrency());
        map.put("merchant_name", merchant.getName());
        map.put("description", intent.getDescription());
        map.put("expires_at", action.getExpiresAt().getEpochSecond());
        map.put("usable", action.isUsable());
        map.put("return_url", action.getReturnUrl());
        return map;
    }

    /** The test-instrument catalogue, so the demo checkout can offer them as one-click options. */
    @GetMapping("/test_instruments")
    public Map<String, Object> testInstruments() {
        List<Map<String, Object>> cards = TestInstruments.cards().stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("number", c.pan());
            map.put("brand", c.brand());
            map.put("funding", c.funding());
            map.put("country", c.country());
            map.put("behaviour", c.behaviour().name());
            map.put("note", c.note());
            return map;
        }).toList();

        List<Map<String, Object>> vpas = TestInstruments.vpas().entrySet().stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("vpa", e.getKey());
            map.put("behaviour", e.getValue().name());
            return map;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cards", cards);
        response.put("upi", vpas);
        response.put("wallets", List.of(
                Map.of("provider", "mockwallet", "behaviour", "ASYNC_SUCCESS"),
                Map.of("provider", "failwallet", "behaviour", "ASYNC_FAILURE")));
        response.put("three_ds_otp", "123456");
        return response;
    }

    // ---------------------------------------------------------------- helpers

    private PaymentIntent authorize(String id, String clientSecret) {
        PaymentIntent intent = intents.findById(id)
                .orElseThrow(() -> ApiException.notFound("payment intent"));
        // Constant-time, because this is a secret being compared against attacker-supplied input.
        if (!Crypto.constantTimeEquals(intent.getClientSecret(), clientSecret)) {
            throw new ApiException(401, "authentication_error", "invalid_client_secret",
                    "The client secret does not match this PaymentIntent.");
        }
        return intent;
    }

    /**
     * A reduced view for untrusted contexts.
     *
     * <p>The full snapshot carries the acquirer, the risk score, and the fee — commercially
     * sensitive detail that has no business being readable by whoever is sitting at the checkout.
     */
    private Map<String, Object> publicSnapshot(PaymentIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", intent.getId());
        map.put("object", "payment_intent");
        map.put("status", intent.getStatus().name().toLowerCase());
        map.put("amount", intent.getAmount());
        map.put("currency", intent.getCurrency());
        map.put("description", intent.getDescription());
        map.put("client_secret", intent.getClientSecret());
        if (intent.getNextActionType() != null) {
            map.put("next_action", Map.of(
                    "type", intent.getNextActionType(),
                    "url", intent.getNextActionUrl() == null ? "" : intent.getNextActionUrl()));
        }
        if (intent.getLastErrorCode() != null) {
            map.put("last_payment_error", Map.of(
                    "code", intent.getLastErrorCode(),
                    "decline_code", intent.getLastDeclineCode() == null ? "" : intent.getLastDeclineCode(),
                    "message", intent.getLastErrorMessage() == null ? "" : intent.getLastErrorMessage()));
        }
        return map;
    }
}
