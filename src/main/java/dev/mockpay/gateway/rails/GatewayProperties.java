package dev.mockpay.gateway.rails;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Tunables, so behaviour that matters commercially is configuration rather than magic numbers. */
@ConfigurationProperties(prefix = "mockpay")
public class GatewayProperties {

    private String publicBaseUrl = "http://localhost:8088";
    private Rail rail = new Rail();
    private Webhook webhook = new Webhook();
    private Idempotency idempotency = new Idempotency();
    private Pricing pricing = new Pricing();
    private SettlementCfg settlement = new SettlementCfg();

    public static class Rail {
        private long minLatencyMs = 120;
        private long maxLatencyMs = 400;
        private double timeoutProbability = 0.0;

        public long getMinLatencyMs() {
            return minLatencyMs;
        }

        public void setMinLatencyMs(long minLatencyMs) {
            this.minLatencyMs = minLatencyMs;
        }

        public long getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(long maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }

        public double getTimeoutProbability() {
            return timeoutProbability;
        }

        public void setTimeoutProbability(double timeoutProbability) {
            this.timeoutProbability = timeoutProbability;
        }
    }

    public static class Webhook {
        private List<Long> backoffSeconds = List.of(5L, 30L, 120L, 600L, 3600L, 21600L);
        private int maxAttempts = 6;
        private long timeoutMs = 4000;

        public List<Long> getBackoffSeconds() {
            return backoffSeconds;
        }

        public void setBackoffSeconds(List<Long> backoffSeconds) {
            this.backoffSeconds = backoffSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Idempotency {
        private int retentionHours = 24;

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }
    }

    /** Merchant discount rate. Cards carry interchange; UPI famously does not. */
    public static class Pricing {
        private int cardBps = 200;
        private long cardFixedMinor = 30;
        private int upiBps = 0;
        private long upiFixedMinor = 0;
        private int walletBps = 180;
        private long walletFixedMinor = 0;

        public int getCardBps() {
            return cardBps;
        }

        public void setCardBps(int cardBps) {
            this.cardBps = cardBps;
        }

        public long getCardFixedMinor() {
            return cardFixedMinor;
        }

        public void setCardFixedMinor(long cardFixedMinor) {
            this.cardFixedMinor = cardFixedMinor;
        }

        public int getUpiBps() {
            return upiBps;
        }

        public void setUpiBps(int upiBps) {
            this.upiBps = upiBps;
        }

        public long getUpiFixedMinor() {
            return upiFixedMinor;
        }

        public void setUpiFixedMinor(long upiFixedMinor) {
            this.upiFixedMinor = upiFixedMinor;
        }

        public int getWalletBps() {
            return walletBps;
        }

        public void setWalletBps(int walletBps) {
            this.walletBps = walletBps;
        }

        public long getWalletFixedMinor() {
            return walletFixedMinor;
        }

        public void setWalletFixedMinor(long walletFixedMinor) {
            this.walletFixedMinor = walletFixedMinor;
        }
    }

    public static class SettlementCfg {
        private int delayDays = 2;

        public int getDelayDays() {
            return delayDays;
        }

        public void setDelayDays(int delayDays) {
            this.delayDays = delayDays;
        }
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Rail getRail() {
        return rail;
    }

    public void setRail(Rail rail) {
        this.rail = rail;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public void setPricing(Pricing pricing) {
        this.pricing = pricing;
    }

    public SettlementCfg getSettlement() {
        return settlement;
    }

    public void setSettlement(SettlementCfg settlement) {
        this.settlement = settlement;
    }
}
