package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.Transaction;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wallets and other alternative payment methods, which split into two genuinely different kinds.
 *
 * <p><b>Pass-through wallets</b> — Apple Pay, Google Pay — are not a payment method at all. They
 * are a nicer front end over a card. What they hand the merchant is a network token plus a
 * cryptogram, and the resulting transaction is an ordinary card authorisation that happens to
 * arrive pre-authenticated. Approval rates are higher precisely because the device already did
 * biometric verification and the token carries proof of it.
 *
 * <p><b>Staged wallets</b> — PayPal, Paytm, and most regional wallets — hold a balance. The customer
 * is redirected to the wallet's own site, authenticates there, and the wallet settles with the
 * merchant separately from however it funded itself. The merchant never sees a card, and the
 * dispute rules are the wallet's, not the card scheme's.
 *
 * <p>The redirect in the staged model is what makes this asynchronous, and it introduces the classic
 * failure: the customer closes the tab after paying but before being returned. The payment
 * succeeded; the merchant's browser callback never fired. This is exactly why the server-to-server
 * webhook, not the browser redirect, must be the source of truth.
 */
@Component
public class WalletSimulator {

    private final GatewayProperties props;

    public WalletSimulator(GatewayProperties props) {
        this.props = props;
    }

    public RailResult initiateRedirect(PaymentIntent intent, PaymentMethod pm, Merchant merchant) {
        long start = System.currentTimeMillis();
        String walletTxnId = "wtx_" + Ids.random(20);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider", pm.getWalletProvider());
        request.put("operation", "CreateOrder");
        request.put("merchantId", merchant.getId());
        request.put("amount", intent.getAmount());
        request.put("currency", intent.getCurrency());
        request.put("reference", intent.getId());
        request.put("returnUrl", props.getPublicBaseUrl() + "/wallet/return");
        request.put("notifyUrl", props.getPublicBaseUrl() + "/wallet/notify");

        sleep();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "CREATED");
        response.put("walletTxnId", walletTxnId);
        response.put("approvalUrl", "(gateway-hosted wallet approval page)");
        response.put("expiresInSeconds", 900);

        return new RailResult(Transaction.Outcome.PENDING, "PENDING",
                "Redirect the customer to the wallet to approve", null, null,
                Ids.numeric(12), walletTxnId, "acq_atlas", pm.getWalletProvider(),
                System.currentTimeMillis() - start, dump(request), dump(response),
                true, "wallet_redirect");
    }

    public RailResult resolveRedirect(PaymentIntent intent, PaymentMethod pm, boolean approved) {
        long start = System.currentTimeMillis();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider", pm.getWalletProvider());
        request.put("operation", "CaptureOrder");
        request.put("walletTxnId", intent.getNetworkTransactionId());

        sleep();

        Map<String, Object> response = new LinkedHashMap<>();
        if (!approved) {
            response.put("status", "CANCELLED");
            response.put("reason", "BUYER_CANCELLED");
            return new RailResult(Transaction.Outcome.DECLINED, "CANCELLED",
                    "Customer cancelled at the wallet", "payer_declined", null, Ids.numeric(12),
                    intent.getNetworkTransactionId(), intent.getAcquirerId(),
                    pm.getWalletProvider(), System.currentTimeMillis() - start,
                    dump(request), dump(response), false, null);
        }

        response.put("status", "COMPLETED");
        response.put("captureId", "cap_" + Ids.random(18));
        return new RailResult(Transaction.Outcome.APPROVED, "00", "Wallet payment completed",
                null, Ids.random(6).toUpperCase(), Ids.numeric(12),
                intent.getNetworkTransactionId(), intent.getAcquirerId(),
                pm.getWalletProvider(), System.currentTimeMillis() - start,
                dump(request), dump(response), false, null);
    }

    public RailResult refund(PaymentIntent intent, PaymentMethod pm, long amount) {
        long start = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "RefundOrder");
        request.put("walletTxnId", intent.getNetworkTransactionId());
        request.put("amount", amount);
        sleep();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "REFUNDED");
        response.put("refundId", "wref_" + Ids.random(16));
        return new RailResult(Transaction.Outcome.APPROVED, "00", "Wallet refund accepted", null,
                null, Ids.numeric(12), intent.getNetworkTransactionId(), intent.getAcquirerId(),
                pm.getWalletProvider(), System.currentTimeMillis() - start,
                dump(request), dump(response), false, null);
    }

    private String dump(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(String.format("%-16s %s%n", k, v)));
        return sb.toString();
    }

    private void sleep() {
        try {
            Thread.sleep(props.getRail().getMinLatencyMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
