package dev.mockpay.gateway.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;

/**
 * Sets {@code Content-Security-Policy: frame-ancestors} on every response.
 *
 * <h2>Why this is a filter of its own</h2>
 *
 * <p>Spring Security already writes security headers, but its filter chain runs only on
 * {@code REQUEST} dispatches by default. Every clean URL in this application is a
 * {@code ViewController} forward — the SPA routes, {@code /checkout}, and
 * {@code /checkout/hosted} — and on a forward the inner dispatch commits the response before the
 * outer chain can add anything. The result is the worst possible split: {@code /checkout/hosted.html}
 * carries the policy and the pretty URL that everyone actually uses does not.
 *
 * <p>Registering explicitly for {@code FORWARD} closes that gap, and being a separate filter makes
 * the guarantee obvious rather than dependent on framework dispatch subtleties.
 *
 * <h2>Why frame-ancestors rather than X-Frame-Options</h2>
 *
 * <p>The hosted payment page is <em>meant</em> to be iframed — that is the whole point of
 * {@code mockpay.open()}, and it is what keeps the merchant's page out of PCI scope.
 * {@code X-Frame-Options} cannot express "these specific origins may embed me": it offers only
 * DENY, SAMEORIGIN, or a single ALLOW-FROM that browsers dropped. {@code frame-ancestors} takes a
 * list, which is exactly the shape of a real gateway's registered-domain allow-list.
 *
 * <p>Leaving it open to everyone would invite clickjacking: an attacker frames the genuine payment
 * page, overlays invisible controls, and harvests whatever the customer types into what looks like
 * a legitimate form.
 */
@Configuration
public class FrameEmbeddingConfig {

    @Bean
    FilterRegistrationBean<Filter> frameAncestorsFilter(
            @Value("${mockpay.frame-ancestors:'self'}") String frameAncestors) {

        Filter filter = (request, response, chain) -> {
            if (response instanceof HttpServletResponse http) {
                // setHeader, not addHeader: a forward can pass through here twice, and two CSP
                // headers are intersected by the browser rather than merged — a second identical
                // policy is harmless, but duplicates are confusing to debug.
                http.setHeader("Content-Security-Policy", "frame-ancestors " + frameAncestors);
            }
            chain.doFilter(request, response);
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(EnumSet.of(
                DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.ERROR));
        registration.addUrlPatterns("/*");
        // Ahead of Spring Security, so the header is on the response before anything can commit it.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("frameAncestorsFilter");
        return registration;
    }
}
