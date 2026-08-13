package dev.mockpay.gateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/** Request bodies. Validation lives on the boundary so nothing malformed reaches the domain. */
public final class Dtos {

    private Dtos() {
    }

    public record CreatePaymentIntentRequest(
            @NotNull @Positive Long amount,
            @NotBlank String currency,
            String capture_method,
            String description,
            String customer,
            String statement_descriptor,
            String payment_method,
            Boolean confirm,
            String return_url,
            Map<String, String> metadata) {
    }

    public record ConfirmPaymentIntentRequest(
            String payment_method,
            String return_url) {
    }

    public record CapturePaymentIntentRequest(Long amount_to_capture) {
    }

    public record CancelPaymentIntentRequest(String cancellation_reason) {
    }

    public record CreatePaymentMethodRequest(
            @NotBlank String type,
            CardDetails card,
            UpiDetails upi,
            WalletDetails wallet) {
    }

    public record CardDetails(String number, Integer exp_month, Integer exp_year, String cvc) {
    }

    public record UpiDetails(String vpa) {
    }

    public record WalletDetails(String provider) {
    }

    public record CreateRefundRequest(
            @NotBlank String payment_intent,
            Long amount,
            String reason) {
    }

    public record CreateDisputeRequest(
            @NotBlank String payment_intent,
            String reason_code,
            Long amount) {
    }

    public record DisputeEvidenceRequest(Map<String, String> evidence) {
    }

    public record ResolveDisputeRequest(@NotNull Boolean merchant_wins) {
    }

    public record RunSettlementRequest(String currency, String period_start, String period_end) {
    }

    public record UpdateWebhookRequest(@NotBlank String url) {
    }
}
