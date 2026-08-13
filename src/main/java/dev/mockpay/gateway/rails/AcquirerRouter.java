package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chooses which acquirer to send a transaction to.
 *
 * <p>This is where a large merchant's money is actually made or lost. Two acquirers presented with
 * the same card will not return the same answer: local acquiring in the cardholder's own country
 * routinely approves several percentage points more than cross-border acquiring, because issuers
 * treat foreign acquirer BINs as higher risk. On a large book, a two-point swing in approval rate
 * dwarfs any saving on processing fees.
 *
 * <p>The routing signals used here are the real ones: card brand, issuer country versus acquirer
 * country, funding type, amount, and recent observed approval rate. Production systems add a
 * circuit breaker per acquirer so that one processor having an outage sheds traffic automatically
 * instead of failing every payment routed to it.
 */
@Component
public class AcquirerRouter {

    public record Acquirer(String id, String name, String country, List<String> brands,
                           int baseFeeBps, boolean supportsUpi) {
    }

    private static final List<Acquirer> ACQUIRERS = List.of(
            new Acquirer("acq_atlas", "Atlas Acquiring (US)", "US",
                    List.of("visa", "mastercard", "amex", "discover"), 195, false),
            new Acquirer("acq_meridian", "Meridian Bank (EU)", "DE",
                    List.of("visa", "mastercard"), 145, false),
            new Acquirer("acq_indus", "Indus Payments (IN)", "IN",
                    List.of("visa", "mastercard", "rupay"), 175, true));

    /** Rolling health, so the router can prefer whatever is currently working. */
    private final Map<String, AtomicLong> approvals = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> attempts = new ConcurrentHashMap<>();

    public List<Acquirer> acquirers() {
        return ACQUIRERS;
    }

    /**
     * Pick an acquirer, and explain why — the explanation is stored on the transaction so a
     * routing decision can be audited after the fact.
     */
    public Decision route(PaymentMethod pm, String currency, long amount, List<String> exclude) {
        if (pm.getType() == PaymentMethod.Type.UPI) {
            return new Decision(ACQUIRERS.get(2),
                    "UPI is a domestic Indian rail; only the India-licensed acquirer can reach the NPCI switch");
        }

        String brand = pm.getCardBrand() == null ? "visa" : pm.getCardBrand();
        String issuerCountry = pm.getCardCountry() == null ? "US" : pm.getCardCountry();

        List<Acquirer> eligible = ACQUIRERS.stream()
                .filter(a -> a.brands().contains(brand))
                .filter(a -> exclude == null || !exclude.contains(a.id()))
                .toList();

        if (eligible.isEmpty()) {
            return new Decision(ACQUIRERS.get(0), "No specialised route available; falling back to default acquirer");
        }

        // Local acquiring first. This single rule is the highest-value routing decision there is.
        for (Acquirer a : eligible) {
            if (a.country().equals(issuerCountry)) {
                return new Decision(a, "Local acquiring: acquirer country " + a.country()
                        + " matches issuer country, avoiding a cross-border authorisation");
            }
        }

        // Otherwise take whichever eligible acquirer is currently approving the most.
        Acquirer best = eligible.stream()
                .max((x, y) -> Double.compare(approvalRate(x.id()), approvalRate(y.id())))
                .orElse(eligible.get(0));
        return new Decision(best, String.format(
                "Cross-border: selected on observed approval rate (%.1f%% over %d recent attempts)",
                approvalRate(best.id()) * 100, attempts.getOrDefault(best.id(), new AtomicLong()).get()));
    }

    public void recordOutcome(String acquirerId, boolean approved) {
        attempts.computeIfAbsent(acquirerId, k -> new AtomicLong()).incrementAndGet();
        if (approved) {
            approvals.computeIfAbsent(acquirerId, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public double approvalRate(String acquirerId) {
        long total = attempts.getOrDefault(acquirerId, new AtomicLong()).get();
        if (total == 0) {
            // No evidence yet. Assume healthy rather than penalising an unused route.
            return 0.9;
        }
        return approvals.getOrDefault(acquirerId, new AtomicLong()).get() / (double) total;
    }

    public record Decision(Acquirer acquirer, String rationale) {
    }
}
