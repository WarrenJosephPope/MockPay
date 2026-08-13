package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Secret-key authentication and per-account rate limiting for the {@code /v1} API.
 *
 * <p>The two-key model is the important idea. A <b>publishable key</b> ships to browsers and can do
 * essentially nothing: identify the account and confirm one specific intent it already holds a
 * client secret for. A <b>secret key</b> never leaves a server and can move money. Keeping them
 * distinct is what makes it safe for a payment form to exist in JavaScript at all.
 *
 * <p>Keys are now resolved by hash against the {@code api_keys} table rather than compared as
 * plaintext columns on the merchant, so a leaked database dump does not hand over the account.
 *
 * <p>Rate limiting sits here rather than deeper in because the traffic worth rejecting is the
 * traffic you want to reject <em>cheaply</em>. Card testing — a fraudster running stolen numbers
 * through a merchant's checkout to see which still work — looks exactly like a burst of
 * authorisation attempts, and each one costs the acquirer a fee whether it approves or not.
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final int REQUESTS_PER_WINDOW = 100;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final ApiKeyService apiKeys;
    private final MerchantRepository merchants;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiKeyFilter(ApiKeyService apiKeys, MerchantRepository merchants) {
        this.apiKeys = apiKeys;
        this.merchants = merchants;
    }

    private record Window(Instant startedAt, AtomicInteger count) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Public surfaces: the hosted checkout, the simulated issuer/PSP pages, static assets, and
        // the client-side confirm endpoints that authenticate with a client secret instead.
        return !path.startsWith("/v1/") || path.startsWith("/v1/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = extractKey(request);

        if (presented == null) {
            unauthorized(response, "no_api_key",
                    "Send your secret key as 'Authorization: Bearer sk_test_...'.");
            return;
        }

        Optional<ApiKey> resolved = apiKeys.resolve(presented);
        if (resolved.isEmpty()) {
            // Deliberately the same response whether the key never existed or was revoked. Telling
            // the caller which is a free oracle for anyone probing with harvested keys.
            unauthorized(response, "invalid_api_key", "No active account matches that API key.");
            return;
        }

        ApiKey key = resolved.get();
        if (key.getType() == ApiKey.Type.PUBLISHABLE) {
            // Almost always a copy-paste error, and saying so plainly saves an hour of debugging.
            unauthorized(response, "publishable_key_not_allowed",
                    "That is a publishable key. This endpoint needs a secret key (sk_...).");
            return;
        }

        Merchant merchant = merchants.findById(key.getMerchantId()).orElse(null);
        if (merchant == null) {
            unauthorized(response, "invalid_api_key", "No active account matches that API key.");
            return;
        }

        if (!allowRequest(merchant.getId())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(WINDOW.getSeconds()));
            response.getWriter().write("""
                    {"error":{"type":"rate_limit_error","code":"rate_limit",\
                    "message":"Too many requests. Back off exponentially and retry."}}""");
            return;
        }

        try {
            RequestContext.set(merchant, key);
            apiKeys.touch(key);
            chain.doFilter(request, response);
        } finally {
            // Non-negotiable: the container will hand this thread to someone else.
            RequestContext.clear();
        }
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // Basic auth with the key as the username is the historical convention, still widely used
        // because it works with curl -u and most HTTP clients without special handling.
        if (header != null && header.startsWith("Basic ")) {
            try {
                String decoded = new String(java.util.Base64.getDecoder()
                        .decode(header.substring(6).trim()));
                return decoded.contains(":") ? decoded.substring(0, decoded.indexOf(':')) : decoded;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** Fixed-window counter. Simple, and adequate at this scale; production uses Redis. */
    private boolean allowRequest(String merchantId) {
        Instant now = Instant.now();
        Window window = windows.compute(merchantId, (id, existing) -> {
            if (existing == null || existing.startedAt().plus(WINDOW).isBefore(now)) {
                return new Window(now, new AtomicInteger(0));
            }
            return existing;
        });
        return window.count().incrementAndGet() <= REQUESTS_PER_WINDOW;
    }

    private void unauthorized(HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"error\":{\"type\":\"authentication_error\",\"code\":\"%s\",\"message\":\"%s\"}}",
                code, message));
    }
}
