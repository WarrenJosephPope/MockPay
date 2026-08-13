package dev.mockpay.gateway.config;

import dev.mockpay.gateway.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Creates the first merchant account on an otherwise empty database, then exits.
 *
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres \
 *       -Dspring-boot.run.arguments="--bootstrap.name=Acme --bootstrap.country=US"
 *
 *   docker compose run --rm gateway --bootstrap.name=Acme --bootstrap.country=US
 * </pre>
 *
 * <p>This exists because of a genuine ordering problem. The demo accounts are seeded only when
 * {@code mockpay.seed-demo-accounts} is on, which must be off anywhere real; and merchant signup
 * does not exist yet. Without a bootstrap path, a production-shaped deployment would come up with
 * no accounts and no way to create one — authentication requires a key, and creating a key requires
 * authentication.
 *
 * <p>It prints the secret key to stdout <b>once</b>. There is no way to recover it afterwards,
 * because only its hash is stored. That is the same contract every real gateway offers, and it is
 * worth experiencing from the operator's side.
 */
@Configuration
public class BootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    @Bean
    @Order(1)
    ApplicationRunner bootstrapAccount(AccountService accounts, ApplicationContext context) {
        return (ApplicationArguments args) -> {
            if (!args.containsOption("bootstrap.name")) {
                return;
            }

            String name = first(args, "bootstrap.name", null);
            String currency = first(args, "bootstrap.currency", "USD");
            String country = first(args, "bootstrap.country", "US");
            String mcc = first(args, "bootstrap.mcc", "5399");

            var created = accounts.create(name, currency, mcc, country);

            // System.out rather than the logger: this is operator output, and it must not be
            // swallowed by a log level or shipped to a log aggregator where the secret would then
            // live forever in someone's search index.
            System.out.printf("""

                    ==========================================================================
                     Account created

                       Account id       %s
                       Name             %s
                       Settles in       %s   (%s, MCC %s)

                       Secret key       %s
                       Publishable key  %s

                     The secret key is shown ONCE. Only its hash is stored; there is no way to
                     recover it. Save it now, then configure a webhook endpoint with:

                       curl -X POST $BASE_URL/v1/webhook_endpoints \\
                         -H "Authorization: Bearer %s" \\
                         -H 'Content-Type: application/json' \\
                         -d '{"url":"https://your-site.example/webhooks"}'
                    ==========================================================================
                    %n""",
                    created.merchant().getId(), created.merchant().getName(),
                    created.merchant().getSettlementCurrency(), country, mcc,
                    created.secretKey(), created.publishableKey(), created.secretKey());

            log.info("Bootstrap complete for {}; exiting.", created.merchant().getId());
            // Exit rather than serve traffic: this invocation was a one-off administrative task,
            // and leaving a server running would be a surprising side effect of creating an account.
            System.exit(SpringApplication.exit(context, () -> 0));
        };
    }

    private String first(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }
}
