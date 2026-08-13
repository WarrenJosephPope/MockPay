package dev.mockpay.gateway;

import dev.mockpay.gateway.rails.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MockPay — a payment gateway that never touches real money, built to be read.
 *
 * <p>Every part of the transaction lifecycle a production gateway performs is here in miniature:
 * tokenisation, risk scoring, acquirer routing, ISO 8583 authorisation, 3-D Secure, capture,
 * refunds, chargebacks, a double-entry ledger, webhook delivery, and net settlement. The rails are
 * simulated; everything in front of them is the real design.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
@EnableScheduling
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
