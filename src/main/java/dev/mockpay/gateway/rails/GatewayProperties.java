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
    private Mail mail = new Mail();

    /** Outbound email. SMTP host, port and credentials are Spring's own `spring.mail.*`. */
    public static class Mail {
        /** The From address on every message the gateway sends. */
        private String from = "MockPay <no-reply@mockpay.local>";

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }
    }

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

        /**
         * The timezone the settlement day is measured in.
         *
         * <p>Not cosmetic. A settlement period is a range of calendar dates, but captures are
         * instants — so "which day did this payment fall on" has no answer until a zone is named.
         * Real acquirers publish a cut-off time in a specific zone for exactly this reason, and
         * every capture after it belongs to the next cycle.
         *
         * <p>It must be applied consistently: computing the default period in the server's local
         * zone and the window in UTC means that, for anyone east of Greenwich, the period after
         * midnight local time is entirely in the future and settles nothing.
         */
        private String zone = "UTC";

        public int getDelayDays() {
            return delayDays;
        }

        public void setDelayDays(int delayDays) {
            this.delayDays = delayDays;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public java.time.ZoneId zoneId() {
            return java.time.ZoneId.of(zone);
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

    public Mail getMail() {
        return mail;
    }

    public void setMail(Mail mail) {
        this.mail = mail;
    }
}
