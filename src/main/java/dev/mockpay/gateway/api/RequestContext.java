package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.ApiKey;
import dev.mockpay.gateway.domain.Merchant;

/**
 * Who is making the current request.
 *
 * <p>Replaces the bare merchant id that used to be threaded around. The reason for a value object
 * rather than a string: everything the request is authorised as tends to grow — today it is the
 * merchant and the key that authenticated it, in a later phase it will also carry the logged-in user
 * and their role. Adding a field here is free; adding a parameter to forty method signatures is not.
 *
 * <p>Held in a thread local and cleared in a {@code finally} by the filter that sets it. That
 * cleanup is not optional: servlet containers reuse threads, and a context left behind hands the
 * next request the previous caller's identity.
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private final Merchant merchant;
    private final ApiKey apiKey;

    private RequestContext(Merchant merchant, ApiKey apiKey) {
        this.merchant = merchant;
        this.apiKey = apiKey;
    }

    public static void set(Merchant merchant, ApiKey apiKey) {
        CURRENT.set(new RequestContext(merchant, apiKey));
    }

    public static RequestContext get() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("No authenticated caller on this request");
        }
        return context;
    }

    public static Merchant merchant() {
        return get().merchant;
    }

    /** The predicate every tenant-scoped query is required to filter on. */
    public static String merchantId() {
        return get().merchant.getId();
    }

    public static ApiKey apiKey() {
        return get().apiKey;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
