package dev.mockpay.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Clean URLs for the simulated third-party pages.
 *
 * <p>These stand in for pages that in production belong to somebody else entirely — the issuer's
 * access control server, the customer's UPI app, the wallet's approval screen. Hosting them here
 * keeps the whole flow runnable on one machine while preserving the important property: the customer
 * genuinely leaves the merchant's page and comes back.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/challenge/3ds").setViewName("forward:/challenge/3ds.html");
        registry.addViewController("/challenge/upi").setViewName("forward:/challenge/upi.html");
        registry.addViewController("/challenge/wallet").setViewName("forward:/challenge/wallet.html");
    }
}
