package dev.mockpay.gateway.api;

import dev.mockpay.gateway.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every failure into the same JSON error envelope.
 *
 * <p>Consistency here is worth more than it looks. An integrator writes one error handler; if some
 * failures come back as this shape and others as a framework stack trace, they end up writing three.
 * The catch-all at the bottom exists so that an unexpected bug produces a clean 500 with a
 * request id, rather than leaking internals to the caller.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", e.getType());
        error.put("code", e.getCode());
        error.put("message", e.getMessage());
        if (e.getDeclineCode() != null) {
            error.put("decline_code", e.getDeclineCode());
        }
        if (e.getPaymentIntentId() != null) {
            error.put("payment_intent", e.getPaymentIntentId());
        }
        return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", error));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "type", "invalid_request_error",
                "code", "parameter_invalid",
                "message", detail.isEmpty() ? "Request validation failed." : detail)));
    }

    /**
     * Two writers touched the same PaymentIntent.
     *
     * <p>409 rather than 500, because it is not a bug and the caller can do something about it: read
     * the current state and decide. A double-clicked pay button lands here, which is exactly the
     * outcome wanted — one authorisation, one clear conflict.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrency(
            ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(409).body(Map.of("error", Map.of(
                "type", "invalid_request_error",
                "code", "concurrent_modification",
                "message", "This object was modified by another request. "
                        + "Fetch its current state before retrying.")));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        // Almost always an invalid state-machine transition, which is a client sequencing error.
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "type", "invalid_request_error",
                "code", "invalid_state_transition",
                "message", e.getMessage() == null ? "Invalid operation for this object." : e.getMessage())));
    }

    /**
     * A path that matches no handler.
     *
     * <p>Without this it falls through to the catch-all and a typo in a URL is reported as
     * {@code 500 internal_error} — telling the caller the server is broken when the truth is that
     * they asked for something that does not exist. Worse, it is indistinguishable from a real
     * outage, so the first instinct is to retry, which is precisely the wrong response to a 404.
     *
     * <p>Spring resolves an unmatched path to the static-resource handler before deciding nothing
     * can serve it, which is why the exception is {@code NoResourceFoundException} rather than
     * anything mentioning routing.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoRoute(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(404).body(Map.of("error", Map.of(
                "type", "invalid_request_error",
                "code", "unknown_endpoint",
                "message", "Unrecognized request URL (" + request.getMethod() + " "
                        + request.getRequestURI() + "). Check the path against the API reference.")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e,
                                                                HttpServletRequest request) {
        String requestId = "req_" + Long.toHexString(System.nanoTime());
        // Logged in full, returned as an opaque id. The caller gets something to quote to support;
        // the internals stay internal.
        log.error("Unhandled error [{}] on {} {}", requestId, request.getMethod(),
                request.getRequestURI(), e);
        return ResponseEntity.status(500).body(Map.of("error", Map.of(
                "type", "api_error",
                "code", "internal_error",
                "request_id", requestId,
                "message", "Something went wrong on our end. Quote " + requestId + " to support.")));
    }
}
