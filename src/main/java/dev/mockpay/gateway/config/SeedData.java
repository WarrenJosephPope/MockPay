package dev.mockpay.gateway.config;

import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.repo.MerchantRepository;
import dev.mockpay.gateway.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Two ready-made merchant accounts, so the API is usable the moment the service starts.
 *
 * <p>Fixed keys rather than random ones: the README, the example requests, the demo checkout and the
 * smoke test all reference them, and a tutorial that starts with "first, find your generated key in
 * the logs" is a worse tutorial.
 *
 * <p>Gated on {@code MOCKPAY_SEED_DEMO_ACCOUNTS}, which defaults to true. <b>Set it to false
 * anywhere real.</b> Publicly documented credentials are a backdoor, not a convenience — and the
 * gate is a property rather than {@code @Profile("dev")} so that the Postgres profile stays usable
 * for local work and for the smoke test without needing a bootstrap step first.
 *
 * <p>Even seeded, the keys are stored as SHA-256 hashes like any other. The documented values keep
 * working because the hash of {@code sk_test_demo_us_secret} is what gets written, not the string.
 */
@Configuration
public class SeedData {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    @Bean
    @Order(2)
    ApplicationRunner seedMerchants(MerchantRepository merchants, AccountService accounts,
                                    GatewayProperties props,
                                    @Value("${mockpay.seed-demo-accounts:true}") boolean seed) {
        return args -> {
            String baseUrl = props.getPublicBaseUrl();

            if (!seed) {
                log.info("Demo account seeding is disabled. Create an account with "
                        + "--bootstrap.name=\"Your Business\"");
                return;
            }
            if (merchants.count() > 0) {
                log.info("Merchants already present; skipping seed. MockPay is up on {}", baseUrl);
                return;
            }

            // Derived from the configured base URL rather than hardcoded, so the seeded accounts
            // still work when the gateway runs on a non-default port.
            String webhookSink = baseUrl + "/webhook-sink";

            accounts.createWithFixedKeys("acct_demo_us", "Demo Store (US)", "USD",
                    // 5399 — miscellaneous general merchandise. The MCC drives interchange rates
                    // and issuer risk models more than most merchants realise.
                    "5399", "US",
                    "sk_test_demo_us_secret", "pk_test_demo_us_publishable");
            accounts.addEndpoint("acct_demo_us", webhookSink,
                    "Built-in sink, so webhook delivery can be watched with no setup", null);

            accounts.createWithFixedKeys("acct_demo_in", "Demo Store (India)", "INR",
                    "5399", "IN",
                    "sk_test_demo_in_secret", "pk_test_demo_in_publishable");
            accounts.addEndpoint("acct_demo_in", webhookSink,
                    "Built-in sink, so webhook delivery can be watched with no setup", null);

            log.info("""

                    ==========================================================================
                     MockPay is up on {}

                       Demo checkout      {}/
                       Test instruments   {}/v1/public/test_instruments
                       Webhook sink       {}/webhook-sink/received

                       US account   sk_test_demo_us_secret   /  pk_test_demo_us_publishable
                       IN account   sk_test_demo_in_secret   /  pk_test_demo_in_publishable

                     Disable these with MOCKPAY_SEED_DEMO_ACCOUNTS=false.
                    ==========================================================================
                    """, baseUrl, baseUrl, baseUrl, baseUrl);
        };
    }
}
