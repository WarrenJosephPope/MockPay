package dev.mockpay.gateway.support;

import java.util.Map;
import java.util.Set;

/**
 * Money handling rules that every payment system converges on.
 *
 * <p>Amounts are integers in the currency's <em>minor unit</em> — 1050 means &euro;10.50 and
 * &#8377;10.50, but 1050 means &yen;1050 because JPY has no minor unit. Floating point is never used:
 * {@code 0.1 + 0.2 != 0.3} in IEEE 754, and a gateway that loses a hundredth of a rupee per
 * transaction fails reconciliation at the end of the day.
 */
public final class Money {

    /** ISO 4217 currencies with no minor unit (exponent 0). */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG",
            "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    /** ISO 4217 currencies with three minor digits (exponent 3). */
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND");

    /** Minimum chargeable amount per currency, in minor units — mirrors real acquirer floors. */
    private static final Map<String, Long> MINIMUM = Map.of(
            "USD", 50L, "EUR", 50L, "GBP", 30L, "INR", 100L, "AED", 200L, "SGD", 50L, "JPY", 50L);

    private Money() {
    }

    public static int exponent(String currency) {
        String c = currency.toUpperCase();
        if (ZERO_DECIMAL.contains(c)) {
            return 0;
        }
        if (THREE_DECIMAL.contains(c)) {
            return 3;
        }
        return 2;
    }

    public static long minimumAmount(String currency) {
        return MINIMUM.getOrDefault(currency.toUpperCase(), 50L);
    }

    /** Human-readable rendering, used only for display and logs — never for arithmetic. */
    public static String format(long minorUnits, String currency) {
        int exp = exponent(currency);
        if (exp == 0) {
            return currency.toUpperCase() + " " + minorUnits;
        }
        long divisor = (long) Math.pow(10, exp);
        long whole = minorUnits / divisor;
        long fraction = Math.abs(minorUnits % divisor);
        return String.format("%s %d.%0" + exp + "d", currency.toUpperCase(), whole, fraction);
    }

    /**
     * Fee arithmetic, rounded half-up to the minor unit.
     *
     * <p>Rounding direction is a real commercial decision: half-up on a 2% fee over a million
     * transactions differs from floor by a few thousand units of currency, so it belongs in one
     * place rather than scattered across call sites.
     */
    public static long applyBps(long amount, int basisPoints) {
        return Math.round((amount * (double) basisPoints) / 10_000d);
    }
}
