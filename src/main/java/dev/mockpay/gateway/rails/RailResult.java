package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.Transaction;

/**
 * What came back from a rail, normalised.
 *
 * <p>The normalisation is the whole job of a gateway's rail layer. Visa says {@code 51}, UPI says
 * {@code U30}, a wallet says {@code INSUFFICIENT_BALANCE}. Every one of those means the customer
 * does not have the money, and every one of them should surface to the merchant as the same
 * {@code insufficient_funds} decline code with the same retry advice. Leaking raw rail codes into
 * merchant-facing APIs is how integrations end up with switch statements over four networks.
 */
public record RailResult(
        Transaction.Outcome outcome,
        /** ISO 8583 DE 39 or the rail's nearest equivalent. Kept for support and audit. */
        String responseCode,
        String responseText,
        /** Stable, cross-rail decline reason. This is what merchants should branch on. */
        String normalizedDeclineCode,
        String authCode,
        String rrn,
        String networkTransactionId,
        String acquirerId,
        String railName,
        long latencyMs,
        String requestDump,
        String responseDump,
        /** True when the customer must do something before this can resolve. */
        boolean requiresAction,
        String actionKind) {

    public boolean approved() {
        return outcome == Transaction.Outcome.APPROVED;
    }

    /**
     * Whether retrying this exact instrument could plausibly work.
     *
     * <p>Soft declines (no funds right now, issuer down, velocity limit) are worth another attempt
     * later or on another acquirer. Hard declines (stolen card, closed account) are not — retrying
     * them burns issuer goodwill, and schemes fine acquirers whose retry rates on hard declines get
     * out of hand.
     */
    public boolean isSoftDecline() {
        if (normalizedDeclineCode == null) {
            return false;
        }
        return switch (normalizedDeclineCode) {
            case "insufficient_funds", "issuer_unavailable", "processing_error",
                 "try_again_later", "velocity_exceeded" -> true;
            default -> false;
        };
    }
}
