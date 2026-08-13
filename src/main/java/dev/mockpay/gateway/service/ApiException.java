package dev.mockpay.gateway.service;

/**
 * A structured, machine-readable API error.
 *
 * <p>Payment errors need more shape than a message string, because merchants have to <em>act</em>
 * on them differently. "Insufficient funds" means show a friendly retry prompt and try again
 * tomorrow. "Stolen card" means never retry and quietly flag the order. "Rate limited" means back
 * off. Collapsing all of these into HTTP 400 with prose forces every integrator to pattern-match on
 * English text, which breaks the moment the wording is improved.
 *
 * <p>The shape mirrors the industry convention: a broad {@code type}, a specific stable
 * {@code code}, and a human-readable message that is safe to log but never safe to parse.
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    /** api_error | card_error | invalid_request_error | idempotency_error | rate_limit_error */
    private final String type;
    private final String code;
    /** Present on declines: the issuer's underlying reason. */
    private final String declineCode;
    private final String paymentIntentId;

    public ApiException(int httpStatus, String type, String code, String message) {
        this(httpStatus, type, code, message, null, null);
    }

    public ApiException(int httpStatus, String type, String code, String message,
                        String declineCode, String paymentIntentId) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
        this.code = code;
        this.declineCode = declineCode;
        this.paymentIntentId = paymentIntentId;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "invalid_request_error", "resource_missing",
                "No such " + what + ", or it belongs to a different account.");
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }
}
