package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.PendingAction;
import dev.mockpay.gateway.domain.Transaction;
import dev.mockpay.gateway.rails.AcquirerRouter;
import dev.mockpay.gateway.rails.CardNetworkSimulator;
import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.rails.RailResult;
import dev.mockpay.gateway.rails.RiskEngine;
import dev.mockpay.gateway.rails.ThreeDsSimulator;
import dev.mockpay.gateway.rails.UpiSimulator;
import dev.mockpay.gateway.rails.WalletSimulator;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.repo.PaymentMethodRepository;
import dev.mockpay.gateway.repo.PendingActionRepository;
import dev.mockpay.gateway.repo.TransactionRepository;
import dev.mockpay.gateway.support.Ids;
import dev.mockpay.gateway.support.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The orchestrator. Everything the gateway knows about how to take money lives here.
 *
 * <p>The order of operations is not arbitrary and is worth reading as a sequence: validate, then
 * score for risk, then authenticate, then authorise, then capture, then book to the ledger, then
 * emit an event. Each step can terminate the flow, and each step is cheaper than the one after it.
 * Screening before authorising saves scheme fees; authenticating before authorising is what earns
 * the liability shift.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentIntentRepository intents;
    private final PaymentMethodRepository paymentMethods;
    private final MerchantRepository merchants;
    private final TransactionRepository transactions;
    private final PendingActionRepository pendingActions;
    private final CardNetworkSimulator cardNetwork;
    private final UpiSimulator upi;
    private final WalletSimulator wallet;
    private final ThreeDsSimulator threeDs;
    private final RiskEngine riskEngine;
    private final AcquirerRouter router;
    private final LedgerService ledger;
    private final EventService events;
    private final GatewayProperties props;

    public PaymentService(PaymentIntentRepository intents, PaymentMethodRepository paymentMethods,
                          MerchantRepository merchants, TransactionRepository transactions,
                          PendingActionRepository pendingActions, CardNetworkSimulator cardNetwork,
                          UpiSimulator upi, WalletSimulator wallet, ThreeDsSimulator threeDs,
                          RiskEngine riskEngine, AcquirerRouter router, LedgerService ledger,
                          EventService events, GatewayProperties props) {
        this.intents = intents;
        this.paymentMethods = paymentMethods;
        this.merchants = merchants;
        this.transactions = transactions;
        this.pendingActions = pendingActions;
        this.cardNetwork = cardNetwork;
        this.upi = upi;
        this.wallet = wallet;
        this.threeDs = threeDs;
        this.riskEngine = riskEngine;
        this.router = router;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public PaymentIntent create(String merchantId, long amount, String currency,
                                PaymentIntent.CaptureMethod captureMethod, String description,
                                String customerRef, String statementDescriptor, String metadataJson) {
        if (amount <= 0) {
            throw new ApiException(400, "invalid_request_error", "amount_too_small",
                    "Amount must be a positive integer in the currency's minor unit.");
        }
        long minimum = Money.minimumAmount(currency);
        if (amount < minimum) {
            // Acquirers enforce a floor because the fixed cost of an authorisation exceeds the fee
            // on a tiny amount — the transaction would lose money for everyone involved.
            throw new ApiException(400, "invalid_request_error", "amount_too_small",
                    "Amount must be at least " + Money.format(minimum, currency) + ".");
        }

        String id = Ids.generate("pi");
        // The client secret is scoped to exactly this intent, so a browser can confirm it without
        // ever holding a key that could touch any other object on the account.
        String clientSecret = id + "_secret_" + Ids.random(24);

        PaymentIntent intent = new PaymentIntent(id, merchantId, amount, currency,
                captureMethod, clientSecret);
        intent.setDescription(description);
        intent.setCustomerRef(customerRef);
        intent.setStatementDescriptor(statementDescriptor);
        intent.setMetadataJson(metadataJson);

        intents.save(intent);
        events.emit(merchantId, "payment_intent.created", snapshot(intent));
        log.info("Created {} for {} {}", id, Money.format(amount, currency), merchantId);
        return intent;
    }

    // ----------------------------------------------------------------- confirm

    /**
     * Attach an instrument and try to take the money.
     *
     * <p>This is the call that can end in five different places: succeeded, requires_capture,
     * requires_action, processing, or failed. A merchant integration that only handles the first two
     * will silently lose every payment that needed a challenge.
     */
    @Transactional
    public PaymentIntent confirm(String merchantId, String intentId, String paymentMethodId,
                                 String returnUrl, String ipAddress, String browserInfo) {
        PaymentIntent intent = mustFind(merchantId, intentId);
        Merchant merchant = merchants.findById(merchantId).orElseThrow();

        if (intent.isTerminal()) {
            throw new ApiException(400, "invalid_request_error", "payment_intent_unexpected_state",
                    "This PaymentIntent is already " + intent.getStatus().name().toLowerCase()
                            + " and cannot be confirmed again.");
        }

        PaymentMethod pm = paymentMethods.findByIdAndMerchantId(paymentMethodId, merchantId)
                .orElseThrow(() -> ApiException.notFound("payment method"));

        intent.setPaymentMethodId(pm.getId());
        intent.setPaymentMethodType(pm.getType());

        // 1. Risk. Cheapest gate, and it runs before anything leaves the building.
        RiskEngine.Assessment risk = riskEngine.assess(intent, pm, ipAddress);
        intent.setRiskScore(risk.score());
        intent.setRiskLevel(risk.level());

        if (risk.recommendation() == RiskEngine.Recommendation.BLOCK) {
            return fail(intent, "card_declined", "blocked_by_risk",
                    "Blocked by risk rules: " + String.join("; ", risk.reasons()));
        }

        return switch (pm.getType()) {
            case CARD -> confirmCard(intent, pm, merchant, risk, browserInfo);
            case UPI -> confirmUpi(intent, pm, merchant);
            case WALLET, NETBANKING -> confirmWallet(intent, pm, merchant, returnUrl);
        };
    }

    private PaymentIntent confirmCard(PaymentIntent intent, PaymentMethod pm, Merchant merchant,
                                      RiskEngine.Assessment risk, String browserInfo) {
        // 2. Authenticate. 3DS runs before authorisation, never after — the whole point is to have
        // the cryptogram in hand when the authorisation message is built.
        boolean wantsChallenge = risk.recommendation() == RiskEngine.Recommendation.CHALLENGE;
        ThreeDsSimulator.AuthenticationResult auth =
                threeDs.authenticate(intent, pm, wantsChallenge, browserInfo);

        recordAuthenticationTransaction(intent, auth);

        if (auth.challengeRequired()) {
            // Park the payment and hand the customer to the issuer. Nothing has been authorised.
            PendingAction action = new PendingAction(Ids.generate("pa"), intent.getId(),
                    intent.getMerchantId(), PendingAction.Kind.THREE_DS_CHALLENGE,
                    "123456", null, Instant.now().plus(Duration.ofMinutes(15)));
            pendingActions.save(action);

            intent.setThreeDsStatus("challenge_required");
            intent.setNextActionType("redirect_to_url");
            intent.setNextActionUrl(props.getPublicBaseUrl() + "/challenge/3ds?action=" + action.getId());
            intent.transitionTo(PaymentIntent.Status.REQUIRES_ACTION);
            intents.save(intent);
            events.emit(intent.getMerchantId(), "payment_intent.requires_action", snapshot(intent));
            return intent;
        }

        intent.setThreeDsStatus("authenticated_frictionless");
        intent.setThreeDsEci(auth.eci());
        return authorizeCard(intent, pm, merchant, auth.cavv(), auth.eci());
    }

    /**
     * 3. Authorise, with a retry on a second acquirer for soft declines.
     *
     * <p>The retry is worth understanding. A "do not honour" from one acquirer's BIN can become an
     * approval through another, because issuer risk models weigh the acquirer's identity and
     * geography. What must never be retried is a hard decline — schemes monitor and fine excessive
     * retry rates, and hammering a stolen-card response is exactly the pattern they look for.
     */
    private PaymentIntent authorizeCard(PaymentIntent intent, PaymentMethod pm, Merchant merchant,
                                        String cavv, String eci) {
        AcquirerRouter.Decision route = router.route(pm, intent.getCurrency(), intent.getAmount(), null);
        RailResult result = cardNetwork.authorize(intent, pm, merchant, route, cavv, eci);
        recordTransaction(intent, Transaction.Type.AUTHORIZATION, intent.getAmount(), result);
        router.recordOutcome(route.acquirer().id(), result.approved());

        if (!result.approved() && result.isSoftDecline()) {
            AcquirerRouter.Decision fallback = router.route(pm, intent.getCurrency(),
                    intent.getAmount(), List.of(route.acquirer().id()));
            if (!fallback.acquirer().id().equals(route.acquirer().id())) {
                log.info("Soft decline ({}) on {}; retrying via {}", result.normalizedDeclineCode(),
                        route.acquirer().id(), fallback.acquirer().id());
                result = cardNetwork.authorize(intent, pm, merchant, fallback, cavv, eci);
                recordTransaction(intent, Transaction.Type.AUTHORIZATION, intent.getAmount(), result);
                router.recordOutcome(fallback.acquirer().id(), result.approved());
            }
        }

        if (!result.approved()) {
            return fail(intent, result.normalizedDeclineCode(), "card_declined", result.responseText());
        }

        intent.setAuthorizationCode(result.authCode());
        intent.setNetworkTransactionId(result.networkTransactionId());
        intent.setAcquirerId(result.acquirerId());
        intent.setAmountCapturable(intent.getAmount());
        intent.setLastErrorCode(null);
        intent.setLastErrorMessage(null);
        intent.setLastDeclineCode(null);

        intent.transitionTo(PaymentIntent.Status.REQUIRES_CAPTURE);
        intents.save(intent);
        events.emit(intent.getMerchantId(), "payment_intent.authorized", snapshot(intent));

        if (intent.getCaptureMethod() == PaymentIntent.CaptureMethod.AUTOMATIC) {
            return capture(intent.getMerchantId(), intent.getId(), null);
        }
        return intent;
    }

    private PaymentIntent confirmUpi(PaymentIntent intent, PaymentMethod pm, Merchant merchant) {
        if (!"INR".equals(intent.getCurrency())) {
            throw new ApiException(400, "invalid_request_error", "unsupported_currency",
                    "UPI settles only in INR.");
        }

        RailResult result = upi.initiateCollect(intent, pm, merchant);
        recordTransaction(intent, Transaction.Type.COLLECT, intent.getAmount(), result);

        PendingAction action = new PendingAction(Ids.generate("pa"), intent.getId(),
                intent.getMerchantId(), PendingAction.Kind.UPI_COLLECT, null, null,
                // NPCI expires unanswered collect requests; leaving them open forever would strand
                // the customer's decision and the merchant's order.
                Instant.now().plus(Duration.ofMinutes(5)));
        pendingActions.save(action);

        intent.setNetworkTransactionId(result.networkTransactionId());
        intent.setAcquirerId(result.acquirerId());
        intent.setNextActionType("upi_await_approval");
        intent.setNextActionUrl(props.getPublicBaseUrl() + "/challenge/upi?action=" + action.getId());
        intent.transitionTo(PaymentIntent.Status.REQUIRES_ACTION);
        intents.save(intent);
        events.emit(intent.getMerchantId(), "payment_intent.requires_action", snapshot(intent));
        return intent;
    }

    private PaymentIntent confirmWallet(PaymentIntent intent, PaymentMethod pm, Merchant merchant,
                                        String returnUrl) {
        RailResult result = wallet.initiateRedirect(intent, pm, merchant);
        recordTransaction(intent, Transaction.Type.AUTHORIZATION, intent.getAmount(), result);

        PendingAction action = new PendingAction(Ids.generate("pa"), intent.getId(),
                intent.getMerchantId(), PendingAction.Kind.WALLET_REDIRECT, null, returnUrl,
                Instant.now().plus(Duration.ofMinutes(15)));
        pendingActions.save(action);

        intent.setNetworkTransactionId(result.networkTransactionId());
        intent.setAcquirerId(result.acquirerId());
        intent.setNextActionType("redirect_to_url");
        intent.setNextActionUrl(props.getPublicBaseUrl() + "/challenge/wallet?action=" + action.getId());
        intent.transitionTo(PaymentIntent.Status.REQUIRES_ACTION);
        intents.save(intent);
        events.emit(intent.getMerchantId(), "payment_intent.requires_action", snapshot(intent));
        return intent;
    }

    // ------------------------------------------------------- resolving actions

    /** The customer finished (or failed) a 3DS challenge. */
    @Transactional
    public PaymentIntent completeThreeDsChallenge(String actionId, String otp) {
        PendingAction action = pendingActions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("pending action"));
        if (!action.isUsable()) {
            throw new ApiException(400, "invalid_request_error", "action_expired",
                    "This authentication session has expired or was already used.");
        }

        PaymentIntent intent = intents.findById(action.getPaymentIntentId()).orElseThrow();
        PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();
        Merchant merchant = merchants.findById(intent.getMerchantId()).orElseThrow();

        boolean otpCorrect = action.getExpectedOtp() != null && action.getExpectedOtp().equals(otp);
        action.setOtpAttempts(action.getOtpAttempts() + 1);

        if (!otpCorrect && action.getOtpAttempts() < 3) {
            // Let them try again; the ACS conventionally allows three.
            pendingActions.save(action);
            throw new ApiException(400, "card_error", "incorrect_otp",
                    "That code was not correct. Attempt " + action.getOtpAttempts() + " of 3.");
        }

        action.setConsumed(true);
        pendingActions.save(action);

        ThreeDsSimulator.AuthenticationResult result = threeDs.completeChallenge(pm, otpCorrect);
        recordAuthenticationTransaction(intent, result);

        if (!"Y".equals(result.transStatus())) {
            intent.setThreeDsStatus("authentication_failed");
            return fail(intent, "authentication_failed", "payment_intent_authentication_failure",
                    "The cardholder could not be authenticated.");
        }

        intent.setThreeDsStatus("authenticated_challenge");
        intent.setThreeDsEci(result.eci());
        intent.setNextActionType(null);
        intent.setNextActionUrl(null);
        return authorizeCard(intent, pm, merchant, result.cavv(), result.eci());
    }

    /** The payer approved or rejected the UPI collect request in their app. */
    @Transactional
    public PaymentIntent resolveUpiCollect(String actionId, boolean approved) {
        PendingAction action = pendingActions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("pending action"));
        if (!action.isUsable()) {
            throw new ApiException(400, "invalid_request_error", "action_expired",
                    "This collect request has expired or was already answered.");
        }
        action.setConsumed(true);
        pendingActions.save(action);

        PaymentIntent intent = intents.findById(action.getPaymentIntentId()).orElseThrow();
        PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();

        RailResult result = upi.resolveCollect(intent, pm, approved);
        recordTransaction(intent, Transaction.Type.AUTHORIZATION, intent.getAmount(), result);

        intent.setNextActionType(null);
        intent.setNextActionUrl(null);

        if (!result.approved()) {
            return fail(intent, result.normalizedDeclineCode(), "payment_failed", result.responseText());
        }

        // UPI has no capture step: the debit and credit already happened, atomically.
        intent.setAmountCapturable(0);
        intent.setAmountReceived(intent.getAmount());
        intent.setCapturedAt(Instant.now());
        intent.transitionTo(PaymentIntent.Status.SUCCEEDED);

        long fee = feeFor(intent);
        intent.setApplicationFee(fee);
        intents.save(intent);
        ledger.recordCapture(intent.getMerchantId(), intent.getCurrency(), intent.getId(),
                intent.getAmount(), fee);
        events.emit(intent.getMerchantId(), "payment_intent.succeeded", snapshot(intent));
        return intent;
    }

    /** The customer came back from the wallet. */
    @Transactional
    public PaymentIntent resolveWalletRedirect(String actionId, boolean approved) {
        PendingAction action = pendingActions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("pending action"));
        if (!action.isUsable()) {
            throw new ApiException(400, "invalid_request_error", "action_expired",
                    "This wallet session has expired or was already completed.");
        }
        action.setConsumed(true);
        pendingActions.save(action);

        PaymentIntent intent = intents.findById(action.getPaymentIntentId()).orElseThrow();
        PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();

        RailResult result = wallet.resolveRedirect(intent, pm, approved);
        recordTransaction(intent, Transaction.Type.AUTHORIZATION, intent.getAmount(), result);

        intent.setNextActionType(null);
        intent.setNextActionUrl(null);

        if (!result.approved()) {
            return fail(intent, result.normalizedDeclineCode(), "payment_failed", result.responseText());
        }

        intent.setAuthorizationCode(result.authCode());
        intent.setAmountCapturable(intent.getAmount());
        intent.transitionTo(PaymentIntent.Status.REQUIRES_CAPTURE);
        intents.save(intent);
        events.emit(intent.getMerchantId(), "payment_intent.authorized", snapshot(intent));

        if (intent.getCaptureMethod() == PaymentIntent.CaptureMethod.AUTOMATIC) {
            return capture(intent.getMerchantId(), intent.getId(), null);
        }
        return intent;
    }

    // ----------------------------------------------------------------- capture

    /**
     * Claim the authorised funds.
     *
     * <p>Capturing less than was authorised is normal and legitimate — the classic case is an order
     * that ships short. Capturing <em>more</em> is not: the issuer only agreed to the authorised
     * amount, and the excess is what a chargeback is made of. Some categories are permitted a small
     * overage (restaurants, for tips; fuel, for the pump total), which is a rule of the merchant
     * category, not of the gateway.
     */
    @Transactional
    public PaymentIntent capture(String merchantId, String intentId, Long amountToCapture) {
        PaymentIntent intent = mustFind(merchantId, intentId);

        if (intent.getStatus() != PaymentIntent.Status.REQUIRES_CAPTURE) {
            throw new ApiException(400, "invalid_request_error", "payment_intent_unexpected_state",
                    "Only an authorised PaymentIntent can be captured; this one is "
                            + intent.getStatus().name().toLowerCase() + ".");
        }

        long amount = amountToCapture == null ? intent.getAmountCapturable() : amountToCapture;
        if (amount <= 0 || amount > intent.getAmountCapturable()) {
            throw new ApiException(400, "invalid_request_error", "amount_too_large",
                    "Capture amount must be between 1 and the authorised "
                            + Money.format(intent.getAmountCapturable(), intent.getCurrency()) + ".");
        }

        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();

        RailResult result = cardNetwork.capture(intent, pm, merchant, amount);
        recordTransaction(intent, Transaction.Type.CAPTURE, amount, result);

        intent.setAmountReceived(intent.getAmountReceived() + amount);
        // Anything authorised but not captured is released rather than left hanging.
        intent.setAmountCapturable(0);
        intent.setCapturedAt(Instant.now());
        intent.transitionTo(PaymentIntent.Status.SUCCEEDED);

        long fee = feeFor(intent);
        intent.setApplicationFee(fee);
        intents.save(intent);

        // Only now does the money exist in the books. An authorisation is a promise; a capture is
        // an entry.
        ledger.recordCapture(merchantId, intent.getCurrency(), intent.getId(),
                intent.getAmountReceived(), fee);
        events.emit(merchantId, "payment_intent.succeeded", snapshot(intent));
        log.info("Captured {} on {}", Money.format(amount, intent.getCurrency()), intent.getId());
        return intent;
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Abandon the payment, reversing any authorisation.
     *
     * <p>Sending the reversal matters. An abandoned hold sits on the cardholder's available balance
     * for days, and it is the single most common cause of "your site charged me twice" complaints —
     * they were not charged twice, they are seeing a stale hold next to a real capture.
     */
    @Transactional
    public PaymentIntent cancel(String merchantId, String intentId, String reason) {
        PaymentIntent intent = mustFind(merchantId, intentId);

        if (intent.isTerminal()) {
            throw new ApiException(400, "invalid_request_error", "payment_intent_unexpected_state",
                    "A " + intent.getStatus().name().toLowerCase() + " PaymentIntent cannot be cancelled."
                            + (intent.getStatus() == PaymentIntent.Status.SUCCEEDED
                            ? " Refund it instead." : ""));
        }

        if (intent.getStatus() == PaymentIntent.Status.REQUIRES_CAPTURE
                && intent.getAmountCapturable() > 0) {
            Merchant merchant = merchants.findById(merchantId).orElseThrow();
            PaymentMethod pm = paymentMethods.findById(intent.getPaymentMethodId()).orElseThrow();
            RailResult result = cardNetwork.voidAuthorization(intent, pm, merchant);
            recordTransaction(intent, Transaction.Type.VOID, intent.getAmountCapturable(), result);
        }

        // Any 3DS or collect session still open is invalidated with it.
        pendingActions.findByPaymentIntentIdAndConsumedFalse(intentId).ifPresent(a -> {
            a.setConsumed(true);
            pendingActions.save(a);
        });

        intent.setAmountCapturable(0);
        intent.setCanceledAt(Instant.now());
        intent.setLastErrorMessage(reason);
        intent.setNextActionType(null);
        intent.setNextActionUrl(null);
        intent.transitionTo(PaymentIntent.Status.CANCELED);
        intents.save(intent);
        events.emit(merchantId, "payment_intent.canceled", snapshot(intent));
        return intent;
    }

    // --------------------------------------------------------------- sweepers

    /**
     * Expire pending actions nobody ever answered.
     *
     * <p>Without this the system leaks: UPI collect requests and 3DS sessions accumulate in
     * {@code requires_action} forever, and merchants have orders they can neither fulfil nor
     * abandon. Every asynchronous rail needs a reaper.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void expireStaleActions() {
        List<PendingAction> stale = pendingActions.findByConsumedFalseAndExpiresAtBefore(Instant.now());
        for (PendingAction action : stale) {
            action.setConsumed(true);
            pendingActions.save(action);

            intents.findById(action.getPaymentIntentId()).ifPresent(intent -> {
                if (intent.getStatus() != PaymentIntent.Status.REQUIRES_ACTION) {
                    return;
                }
                log.info("Expiring {} — customer never completed {}", intent.getId(), action.getKind());
                intent.setNextActionType(null);
                intent.setNextActionUrl(null);
                fail(intent, "action_expired", "payment_intent_action_expired",
                        "The customer did not complete authentication in time.");
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private PaymentIntent fail(PaymentIntent intent, String declineCode, String errorCode,
                               String message) {
        intent.setLastDeclineCode(declineCode);
        intent.setLastErrorCode(errorCode);
        intent.setLastErrorMessage(message);
        intent.setAmountCapturable(0);
        // Failure is not terminal by design: the merchant can attach a different card and retry the
        // same intent, which keeps one id per order rather than one per attempt.
        intent.transitionTo(PaymentIntent.Status.FAILED);
        intents.save(intent);
        events.emit(intent.getMerchantId(), "payment_intent.payment_failed", snapshot(intent));
        log.info("Failed {}: {} ({})", intent.getId(), message, declineCode);
        return intent;
    }

    private long feeFor(PaymentIntent intent) {
        var pricing = props.getPricing();
        return switch (intent.getPaymentMethodType()) {
            case UPI -> Money.applyBps(intent.getAmountReceived(), pricing.getUpiBps())
                    + pricing.getUpiFixedMinor();
            case WALLET, NETBANKING -> Money.applyBps(intent.getAmountReceived(), pricing.getWalletBps())
                    + pricing.getWalletFixedMinor();
            default -> Money.applyBps(intent.getAmountReceived(), pricing.getCardBps())
                    + pricing.getCardFixedMinor();
        };
    }

    private void recordTransaction(PaymentIntent intent, Transaction.Type type, long amount,
                                   RailResult result) {
        Transaction txn = new Transaction(Ids.generate("txn"), intent.getId(), intent.getMerchantId(),
                type, amount, intent.getCurrency());
        txn.setOutcome(result.outcome());
        txn.setResponseCode(result.responseCode());
        txn.setResponseText(result.responseText());
        txn.setAuthCode(result.authCode());
        txn.setRrn(result.rrn());
        txn.setAcquirerId(result.acquirerId());
        txn.setRailName(result.railName());
        txn.setLatencyMs(result.latencyMs());
        txn.setRequestDump(truncate(result.requestDump(), 3900));
        txn.setResponseDump(truncate(result.responseDump(), 3900));
        txn.setMti(firstMti(result.requestDump()));
        transactions.save(txn);
    }

    private void recordAuthenticationTransaction(PaymentIntent intent,
                                                 ThreeDsSimulator.AuthenticationResult auth) {
        Transaction txn = new Transaction(Ids.generate("txn"), intent.getId(), intent.getMerchantId(),
                Transaction.Type.AUTHENTICATION, intent.getAmount(), intent.getCurrency());
        txn.setOutcome("Y".equals(auth.transStatus()) ? Transaction.Outcome.APPROVED
                : "C".equals(auth.transStatus()) ? Transaction.Outcome.PENDING
                : Transaction.Outcome.DECLINED);
        txn.setResponseCode(auth.transStatus());
        txn.setResponseText(auth.reason());
        txn.setRailName("3ds");
        txn.setRequestDump(truncate(mapDump(auth.areq()), 3900));
        txn.setResponseDump(truncate(mapDump(auth.ares()), 3900));
        transactions.save(txn);
    }

    private String mapDump(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(String.format("%-28s %s%n", k, v)));
        return sb.toString();
    }

    private String firstMti(String dump) {
        if (dump == null || !dump.startsWith("MTI")) {
            return null;
        }
        String[] parts = dump.split("\\s+");
        return parts.length > 1 ? parts[1] : null;
    }

    private String truncate(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    public PaymentIntent mustFind(String merchantId, String intentId) {
        return intents.findByIdAndMerchantId(intentId, merchantId)
                .orElseThrow(() -> ApiException.notFound("payment intent"));
    }

    public List<Transaction> transactionsFor(String merchantId, String intentId) {
        mustFind(merchantId, intentId);
        return transactions.findByPaymentIntentIdOrderByCreatedAtAsc(intentId);
    }

    /** The public JSON shape of a PaymentIntent, used for both API responses and webhook payloads. */
    public Map<String, Object> snapshot(PaymentIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", intent.getId());
        map.put("object", "payment_intent");
        map.put("amount", intent.getAmount());
        map.put("amount_capturable", intent.getAmountCapturable());
        map.put("amount_received", intent.getAmountReceived());
        map.put("amount_refunded", intent.getAmountRefunded());
        map.put("currency", intent.getCurrency());
        map.put("status", intent.getStatus().name().toLowerCase());
        map.put("capture_method", intent.getCaptureMethod().name().toLowerCase());
        map.put("client_secret", intent.getClientSecret());
        map.put("payment_method", intent.getPaymentMethodId());
        map.put("payment_method_type", intent.getPaymentMethodType() == null ? null
                : intent.getPaymentMethodType().name().toLowerCase());
        map.put("description", intent.getDescription());
        map.put("customer", intent.getCustomerRef());
        map.put("statement_descriptor", intent.getStatementDescriptor());
        map.put("authorization_code", intent.getAuthorizationCode());
        map.put("network_transaction_id", intent.getNetworkTransactionId());
        map.put("acquirer", intent.getAcquirerId());
        map.put("application_fee_amount", intent.getApplicationFee());
        map.put("created", intent.getCreatedAt().getEpochSecond());

        if (intent.getRiskScore() != null) {
            map.put("risk", Map.of("score", intent.getRiskScore(), "level", intent.getRiskLevel()));
        }
        if (intent.getThreeDsStatus() != null) {
            Map<String, Object> tds = new LinkedHashMap<>();
            tds.put("status", intent.getThreeDsStatus());
            tds.put("eci", intent.getThreeDsEci());
            tds.put("liability_shifted", "05".equals(intent.getThreeDsEci()));
            map.put("three_d_secure", tds);
        }
        if (intent.getNextActionType() != null) {
            map.put("next_action", Map.of(
                    "type", intent.getNextActionType(),
                    "url", intent.getNextActionUrl() == null ? "" : intent.getNextActionUrl()));
        }
        if (intent.getLastErrorCode() != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", intent.getLastErrorCode());
            err.put("decline_code", intent.getLastDeclineCode());
            err.put("message", intent.getLastErrorMessage());
            map.put("last_payment_error", err);
        }
        return map;
    }
}
