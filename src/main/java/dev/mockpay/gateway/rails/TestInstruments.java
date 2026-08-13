package dev.mockpay.gateway.rails;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of deterministic test instruments.
 *
 * <p>Every real gateway ships a table like this, because "sometimes it declines" is untestable. A
 * fixed card number per failure mode lets you write an assertion for the insufficient-funds path
 * and have it mean something.
 *
 * <p>The numbers here follow the same convention the industry uses: they are Luhn-valid but sit in
 * BIN ranges reserved for testing, so they can never route to a real issuer.
 */
public final class TestInstruments {

    /** What the simulated issuer will do with a given instrument. */
    public enum Behaviour {
        APPROVE,
        DECLINE_GENERIC,
        DECLINE_INSUFFICIENT_FUNDS,
        DECLINE_EXPIRED_CARD,
        DECLINE_INCORRECT_CVC,
        DECLINE_LOST_CARD,
        DECLINE_STOLEN_CARD,
        /** Issuer or switch unreachable — a "soft" decline that is worth retrying. */
        ISSUER_UNAVAILABLE,
        /** 3DS challenge required, and the challenge will succeed. */
        THREE_DS_REQUIRED_SUCCESS,
        /** 3DS challenge required, and the cardholder will fail it. */
        THREE_DS_REQUIRED_FAIL,
        /** Blocked by the gateway's own risk engine before it ever reaches the network. */
        RISK_BLOCK,
        /** Rail accepts, then resolves asynchronously (UPI collect, wallet redirect). */
        ASYNC_SUCCESS,
        ASYNC_FAILURE,
        /** Customer never responds; the request expires. */
        ASYNC_TIMEOUT
    }

    public record CardProfile(String pan, Behaviour behaviour, String brand, String funding,
                              String issuer, String country, String note) {
    }

    private static final List<CardProfile> CARDS = List.of(
            new CardProfile("4242424242424242", Behaviour.APPROVE, "visa", "credit",
                    "Mock Issuer Bank", "US", "Baseline approval"),
            new CardProfile("4000056655665556", Behaviour.APPROVE, "visa", "debit",
                    "Mock Issuer Bank", "US", "Debit funding — different interchange"),
            new CardProfile("5555555555554444", Behaviour.APPROVE, "mastercard", "credit",
                    "Mock Issuer Bank", "US", "Mastercard approval"),
            new CardProfile("378282246310005", Behaviour.APPROVE, "amex", "credit",
                    "Mock Amex", "US", "Amex — 15 digits, 4-digit CID"),
            new CardProfile("6521000000000008", Behaviour.APPROVE, "rupay", "debit",
                    "Mock Indian Bank", "IN", "RuPay domestic — settles via NPCI, not Visa/MC"),
            new CardProfile("4000000000000002", Behaviour.DECLINE_GENERIC, "visa", "credit",
                    "Mock Issuer Bank", "US", "Generic decline (DE39 05, do not honour)"),
            new CardProfile("4000000000009995", Behaviour.DECLINE_INSUFFICIENT_FUNDS, "visa", "debit",
                    "Mock Issuer Bank", "US", "Insufficient funds (DE39 51) — soft, retry later"),
            new CardProfile("4000000000000069", Behaviour.DECLINE_EXPIRED_CARD, "visa", "credit",
                    "Mock Issuer Bank", "US", "Expired card (DE39 54) — hard, ask for a new card"),
            new CardProfile("4000000000000127", Behaviour.DECLINE_INCORRECT_CVC, "visa", "credit",
                    "Mock Issuer Bank", "US", "Incorrect CVC (DE39 82)"),
            new CardProfile("4000000000000259", Behaviour.DECLINE_LOST_CARD, "visa", "credit",
                    "Mock Issuer Bank", "US", "Lost card (DE39 41) — hard, never retry"),
            new CardProfile("4000000000004954", Behaviour.DECLINE_STOLEN_CARD, "visa", "credit",
                    "Mock Issuer Bank", "US", "Stolen card (DE39 43) — hard, never retry"),
            new CardProfile("4000000000000119", Behaviour.ISSUER_UNAVAILABLE, "visa", "credit",
                    "Mock Issuer Bank", "US", "Issuer unavailable (DE39 91) — retry or route away"),
            new CardProfile("4000002500003155", Behaviour.THREE_DS_REQUIRED_SUCCESS, "visa", "credit",
                    "Mock EU Issuer", "DE", "3DS challenge, then approves. OTP is 123456"),
            new CardProfile("4000008400001629", Behaviour.THREE_DS_REQUIRED_FAIL, "visa", "credit",
                    "Mock EU Issuer", "DE", "3DS challenge that the cardholder fails"),
            new CardProfile("4100000000000019", Behaviour.RISK_BLOCK, "visa", "credit",
                    "Mock Issuer Bank", "US", "Blocked by risk engine before the network sees it"));

    private static final Map<String, Behaviour> VPA_BEHAVIOUR = new LinkedHashMap<>(Map.of(
            "success@mockpay", Behaviour.ASYNC_SUCCESS,
            "failure@mockpay", Behaviour.ASYNC_FAILURE,
            "pending@mockpay", Behaviour.ASYNC_TIMEOUT,
            "insufficient@mockpay", Behaviour.DECLINE_INSUFFICIENT_FUNDS,
            "risk@mockpay", Behaviour.RISK_BLOCK));

    private TestInstruments() {
    }

    public static List<CardProfile> cards() {
        return CARDS;
    }

    public static Map<String, Behaviour> vpas() {
        return VPA_BEHAVIOUR;
    }

    public static CardProfile lookupCard(String pan) {
        String normalised = pan == null ? "" : pan.replaceAll("[^0-9]", "");
        return CARDS.stream()
                .filter(c -> c.pan().equals(normalised))
                .findFirst()
                // Unknown but well-formed numbers approve, so exploratory testing is not blocked
                // by having to memorise the table.
                .orElse(new CardProfile(normalised, Behaviour.APPROVE, brandFor(normalised),
                        "credit", "Mock Issuer Bank", "US", "Unlisted test card — approves"));
    }

    public static Behaviour lookupVpa(String vpa) {
        return VPA_BEHAVIOUR.getOrDefault(vpa == null ? "" : vpa.toLowerCase(), Behaviour.ASYNC_SUCCESS);
    }

    /**
     * Brand detection from the leading digits, the same way a real BIN table works — the network is
     * a property of the number range, not something the customer chooses.
     */
    public static String brandFor(String pan) {
        if (pan == null || pan.isEmpty()) {
            return "unknown";
        }
        if (pan.startsWith("4")) {
            return "visa";
        }
        if (pan.matches("^(5[1-5]|2[2-7]).*")) {
            return "mastercard";
        }
        if (pan.matches("^3[47].*")) {
            return "amex";
        }
        if (pan.matches("^(60|65|81|82|508).*")) {
            return "rupay";
        }
        if (pan.startsWith("6011") || pan.startsWith("65")) {
            return "discover";
        }
        return "unknown";
    }

    /**
     * The Luhn check.
     *
     * <p>It is a checksum, not security: it catches typos and transposed digits at the point of
     * entry so you do not spend a network round trip discovering that someone fat-fingered a digit.
     * Every card number in circulation satisfies it, and so does every random number a fraudster
     * generates, which is exactly why it proves nothing about validity.
     */
    public static boolean luhnValid(String pan) {
        String digits = pan == null ? "" : pan.replaceAll("[^0-9]", "");
        if (digits.length() < 12 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
