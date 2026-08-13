package dev.mockpay.gateway.config;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.repo.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two ready-made merchant accounts, so the API is usable the moment the service starts.
 *
 * <p>Fixed keys rather than random ones: the README, the example requests, and the demo checkout all
 * reference them, and a tutorial that starts with "first, find your generated key in the logs" is a
 * worse tutorial.
 */
@Configuration
public class SeedData {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    @Bean
    ApplicationRunner seedMerchants(MerchantRepository merchants, GatewayProperties props) {
        return args -> {
            String baseUrl = props.getPublicBaseUrl();

            if (merchants.count() > 0) {
                log.info("Merchants already present; skipping seed. MockPay is up on {}", baseUrl);
                return;
            }

            // Derived from the configured base URL rather than hardcoded, so the seeded accounts
            // still work when the gateway runs on a non-default port. Point it elsewhere with
            // POST /v1/account/webhook.
            String webhookSink = baseUrl + "/webhook-sink";

            merchants.save(new Merchant(
                    "acct_demo_us", "Demo Store (US)",
                    "pk_test_demo_us_publishable",
                    "sk_test_demo_us_secret",
                    "whsec_demo_us_1234567890abcdef",
                    webhookSink,
                    "USD",
                    // 5399 — miscellaneous general merchandise. The MCC drives interchange rates
                    // and issuer risk models more than most merchants realise.
                    "5399", "US"));

            merchants.save(new Merchant(
                    "acct_demo_in", "Demo Store (India)",
                    "pk_test_demo_in_publishable",
                    "sk_test_demo_in_secret",
                    "whsec_demo_in_1234567890abcdef",
                    webhookSink,
                    "INR",
                    "5399", "IN"));

            log.info("""

                    ==========================================================================
                     MockPay is up on {}

                       Demo checkout      {}/
                       Test instruments   {}/v1/public/test_instruments
                       Webhook sink       {}/webhook-sink/received

                       US account   sk_test_demo_us_secret   /  pk_test_demo_us_publishable
                       IN account   sk_test_demo_in_secret   /  pk_test_demo_in_publishable
                    ==========================================================================
                    """, baseUrl, baseUrl, baseUrl, baseUrl);
        };
    }
}
