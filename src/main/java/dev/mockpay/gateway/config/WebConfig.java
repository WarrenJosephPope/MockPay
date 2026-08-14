package dev.mockpay.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Routing for everything that is not an API call.
 *
 * <p>Two jobs. Clean URLs for the simulated third-party pages — in production those belong to the
 * issuer's access control server, the customer's UPI app and the wallet, and hosting them here is
 * what keeps the whole flow runnable on one machine. And the SPA fallback, so that a deep link into
 * the React dashboard survives a page refresh.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Simulated third parties.
        registry.addViewController("/challenge/3ds").setViewName("forward:/challenge/3ds.html");
        registry.addViewController("/challenge/upi").setViewName("forward:/challenge/upi.html");
        registry.addViewController("/challenge/wallet").setViewName("forward:/challenge/wallet.html");

        // The demo storefront. It used to be index.html; the dashboard owns that now.
        registry.addViewController("/checkout").setViewName("forward:/checkout.html");

        // The hosted payment page, which merchants embed in an iframe. Clean URL because it is
        // part of the public integration surface, not an implementation detail.
        registry.addViewController("/checkout/hosted").setViewName("forward:/checkout/hosted.html");

        // SPA fallback.
        //
        // React Router owns these paths in the browser, but a refresh or a pasted link asks the
        // server for them, and there is no such file. Forwarding to index.html lets the client
        // router take over.
        //
        // Enumerated deliberately rather than matched with a catch-all: a greedy pattern would
        // swallow /v1/**, and an unknown API path would return an HTML page with status 200
        // instead of a JSON 404 — which turns a clear client bug into a baffling parse error.
        for (String route : new String[]{
                "/", "/login", "/signup", "/forgot-password", "/reset-password",
                "/accept-invitation", "/payments", "/api-keys", "/endpoints", "/events",
                "/team", "/audit", "/settings"}) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
        // One level of path variable, for /payments/pi_123 and friends.
        registry.addViewController("/payments/*").setViewName("forward:/index.html");
    }
}
