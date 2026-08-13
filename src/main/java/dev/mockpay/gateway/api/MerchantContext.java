package dev.mockpay.gateway.api;

import dev.mockpay.gateway.domain.Merchant;

/**
 * The authenticated caller for the current request.
 *
 * <p>Held in a thread local rather than threaded through every method signature, and cleared in a
 * {@code finally} block by the filter that sets it. That cleanup is not optional: application
 * servers reuse threads, and a context left behind would hand the next request the previous
 * merchant's identity — an authorisation bug that no amount of query-level scoping can catch.
 */
public final class MerchantContext {

    private static final ThreadLocal<Merchant> CURRENT = new ThreadLocal<>();

    private MerchantContext() {
    }

    public static void set(Merchant merchant) {
        CURRENT.set(merchant);
    }

    public static Merchant get() {
        Merchant merchant = CURRENT.get();
        if (merchant == null) {
            throw new IllegalStateException("No authenticated merchant on this request");
        }
        return merchant;
    }

    public static String merchantId() {
        return get().getId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
