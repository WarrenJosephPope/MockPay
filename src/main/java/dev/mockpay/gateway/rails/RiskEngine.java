package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pre-authorisation risk scoring.
 *
 * <p>Fraud screening happens <em>before</em> the network call for a reason that is purely
 * commercial: every authorisation attempt costs money, and an acquirer whose fraud-to-sales ratio
 * crosses scheme thresholds enters a monitoring programme with escalating fines. Declining a
 * transaction yourself is cheaper than letting the issuer decline it.
 *
 * <p>The signals modelled here are the classic ones. Real engines add device fingerprinting,
 * behavioural biometrics, and gradient-boosted models over hundreds of features — but the shape is
 * the same: accumulate weighted signals, compare to a threshold, and choose allow / challenge /
 * block. Note the middle option: pushing a borderline payment into a 3DS challenge shifts fraud
 * liability to the issuer instead of rejecting a possibly-good customer.
 */
@Component
public class RiskEngine {

    public enum Recommendation {
        ALLOW,
        /** Force a step-up (3DS). Converts risk into issuer liability. */
        CHALLENGE,
        BLOCK
    }

    public record Assessment(int score, String level, Recommendation recommendation, List<String> reasons) {
    }

    /** In-memory velocity windows. Production uses Redis with sliding-window counters. */
    private final Map<String, CopyOnWriteArrayList<Instant>> velocityByFingerprint = new ConcurrentHashMap<>();

    public Assessment assess(PaymentIntent intent, PaymentMethod pm, String ipAddress) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (TestInstruments.Behaviour.RISK_BLOCK.name().equals(pm.getSimulatedBehaviour())) {
            return new Assessment(99, "highest", Recommendation.BLOCK,
                    List.of("Instrument is on the blocklist"));
        }

        // Velocity: the same card hammering the same merchant is the oldest fraud signal there is,
        // and it is what card-testing attacks look like from the gateway's side.
        String key = pm.getCardFingerprint() != null ? pm.getCardFingerprint() : pm.getId();
        var window = velocityByFingerprint.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        window.removeIf(t -> t.isBefore(cutoff));
        window.add(Instant.now());
        if (window.size() > 25) {
            score += 45;
            reasons.add("Velocity: " + window.size() + " attempts on this instrument in 10 minutes");
        } else if (window.size() > 12) {
            score += 20;
            reasons.add("Elevated velocity: " + window.size() + " attempts in 10 minutes");
        }

        // Ticket size. Fraud skews high because a stolen card has a short useful life.
        if (intent.getAmount() > 50_000_00L) {
            score += 25;
            reasons.add("High ticket value");
        } else if (intent.getAmount() > 5_000_00L) {
            score += 10;
            reasons.add("Above-average ticket value");
        }

        // Cross-border. Legitimate, but empirically riskier.
        if (pm.getCardCountry() != null && !pm.getCardCountry().equals("US")
                && "USD".equals(intent.getCurrency())) {
            score += 12;
            reasons.add("Issuer country does not match transaction currency");
        }

        // Prepaid cards are disproportionately used for card testing: anonymous and disposable.
        if ("prepaid".equals(pm.getCardFunding())) {
            score += 15;
            reasons.add("Prepaid funding instrument");
        }

        if (ipAddress != null && ipAddress.startsWith("10.99.")) {
            score += 30;
            reasons.add("Request IP is on a known-bad range");
        }

        if (reasons.isEmpty()) {
            reasons.add("No adverse signals");
        }

        String level = score >= 70 ? "highest" : score >= 40 ? "elevated" : score >= 20 ? "normal" : "low";
        Recommendation rec = score >= 70 ? Recommendation.BLOCK
                : score >= 40 ? Recommendation.CHALLENGE
                : Recommendation.ALLOW;

        return new Assessment(score, level, rec, reasons);
    }
}
