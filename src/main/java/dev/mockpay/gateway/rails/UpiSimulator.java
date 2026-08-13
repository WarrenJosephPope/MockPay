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
 * India's Unified Payments Interface — a rail with fundamentally different physics to cards.
 *
 * <p>Four differences matter to anyone building on it:
 *
 * <ol>
 *   <li><b>There is no authorisation/capture split.</b> A UPI transaction debits the payer's bank
 *       account and credits the payee's in one atomic operation. You cannot hold funds and claim
 *       them later, so the entire "authorise now, capture on shipment" pattern does not exist.
 *   <li><b>Settlement is near-real-time</b>, in NPCI settlement cycles running many times a day
 *       rather than as an overnight batch.
 *   <li><b>There is no chargeback in the card sense.</b> Disputes exist, but there is no equivalent
 *       of a cardholder unilaterally reversing a payment months later, which changes a merchant's
 *       risk profile completely.
 *   <li><b>Interchange is essentially zero</b> for person-to-merchant payments, by regulatory
 *       design. This is why Indian merchants push UPI over cards.
 * </ol>
 *
 * <p>Addressing is by Virtual Payment Address ({@code name@bank}), which resolves to an account
 * number without ever exposing it. The two flows are <b>pay</b> (payer pushes, typically by scanning
 * a QR) and <b>collect</b> (payee requests, and the payer approves in their own app). Collect is
 * inherently asynchronous — the customer may take minutes, or never respond at all — which is why
 * this simulator returns PENDING rather than a verdict.
 */
@Component
public class UpiSimulator {

    private final GatewayProperties props;

    public UpiSimulator(GatewayProperties props) {
        this.props = props;
    }

    /**
     * Sends a collect request to the payer's PSP.
     *
     * <p>The gateway's PSP bank signs the request and hands it to the NPCI switch, which resolves
     * the VPA to an issuing bank and pushes a notification into the payer's UPI app. Nothing has
     * moved yet; this call succeeding only means the request was accepted for delivery.
     */
    public RailResult initiateCollect(PaymentIntent intent, PaymentMethod pm, Merchant merchant) {
        long start = System.currentTimeMillis();
        String rrn = Ids.numeric(12);
        String txnId = "MOCK" + Ids.random(28).toUpperCase();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("api", "ReqPay");
        request.put("ver", "2.0");
        request.put("txnId", txnId);
        // Purpose code 00 = default; the taxonomy drives NPCI reporting and limits.
        request.put("purpose", "00");
        request.put("payerVpa", pm.getUpiVpa());
        request.put("payeeVpa", "mockpay." + merchant.getId().toLowerCase() + "@mockbank");
        request.put("amount", formatInr(intent.getAmount()));
        request.put("currency", "INR");
        request.put("refId", intent.getId());
        request.put("refUrl", props.getPublicBaseUrl());
        request.put("note", truncate(intent.getDescription(), 50));
        // Real ReqPay carries a signed XML envelope; the PSP's key is what NPCI authenticates.
        request.put("signature", "<XMLDSig by PSP certificate>");

        sleep(80, 200);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("api", "RespPay");
        response.put("txnId", txnId);
        // U00 is "accepted for processing" — an acknowledgement, not an outcome.
        response.put("result", "SUCCESS");
        response.put("errCode", "U00");
        response.put("rrn", rrn);
        response.put("note", "Collect request delivered to payer PSP; awaiting cardholder approval");

        return new RailResult(Transaction.Outcome.PENDING, "U00",
                "Collect request sent to payer's UPI app", null, null, rrn, txnId,
                "acq_indus", "upi", System.currentTimeMillis() - start,
                dump(request), dump(response), true, "upi_collect_approval");
    }

    /**
     * The payer opened their app, saw the request, and entered their UPI PIN (or declined).
     *
     * <p>The PIN never reaches the gateway or even the PSP app in usable form: it is captured by an
     * NPCI-certified common library, encrypted against the issuing bank's key, and passed through as
     * an opaque blob. This is the second factor in UPI's "1-click 2-factor" model — device binding
     * is the first.
     */
    public RailResult resolveCollect(PaymentIntent intent, PaymentMethod pm, boolean approved) {
        long start = System.currentTimeMillis();
        String rrn = Ids.numeric(12);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("api", "ReqAuthDetails");
        request.put("txnId", intent.getNetworkTransactionId());
        request.put("payerVpa", pm.getUpiVpa());
        request.put("credential", "{ type: PIN, encrypted with issuer key, NPCI CL }");

        sleep(60, 160);

        TestInstruments.Behaviour behaviour = behaviourOf(pm);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("api", "RespAuthDetails");
        response.put("rrn", rrn);

        if (!approved || behaviour == TestInstruments.Behaviour.ASYNC_FAILURE) {
            response.put("result", "FAILURE");
            response.put("errCode", "ZA");
            response.put("errDesc", "Transaction declined by payer");
            return new RailResult(Transaction.Outcome.DECLINED, "ZA",
                    "Payer declined the collect request", "payer_declined", null, rrn,
                    intent.getNetworkTransactionId(), "acq_indus", "upi",
                    System.currentTimeMillis() - start, dump(request), dump(response), false, null);
        }

        if (behaviour == TestInstruments.Behaviour.DECLINE_INSUFFICIENT_FUNDS) {
            response.put("result", "FAILURE");
            // U30 is the debit-failure family; the normalised code is what merchants should read.
            response.put("errCode", "U30");
            response.put("errDesc", "Debit failed - insufficient balance");
            return new RailResult(Transaction.Outcome.DECLINED, "U30",
                    "Debit failed at remitter bank", "insufficient_funds", null, rrn,
                    intent.getNetworkTransactionId(), "acq_indus", "upi",
                    System.currentTimeMillis() - start, dump(request), dump(response), false, null);
        }

        response.put("result", "SUCCESS");
        response.put("errCode", "00");
        // Debit and credit are one operation. There is no separate capture to perform.
        response.put("note", "Payer account debited and payee account credited");
        return new RailResult(Transaction.Outcome.APPROVED, "00",
                "UPI transaction successful", null, null, rrn,
                intent.getNetworkTransactionId(), "acq_indus", "upi",
                System.currentTimeMillis() - start, dump(request), dump(response), false, null);
    }

    /** UPI refunds go back over the same rail as a fresh credit, referencing the original RRN. */
    public RailResult refund(PaymentIntent intent, PaymentMethod pm, long amount) {
        long start = System.currentTimeMillis();
        String rrn = Ids.numeric(12);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("api", "ReqPay");
        request.put("subType", "REFUND");
        request.put("origTxnId", intent.getNetworkTransactionId());
        request.put("amount", formatInr(amount));

        sleep(60, 160);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", "SUCCESS");
        response.put("errCode", "00");
        response.put("rrn", rrn);

        return new RailResult(Transaction.Outcome.APPROVED, "00", "UPI refund accepted", null,
                null, rrn, intent.getNetworkTransactionId(), "acq_indus", "upi",
                System.currentTimeMillis() - start, dump(request), dump(response), false, null);
    }

    /** UPI amounts are expressed in rupees with two decimals, not in paise. */
    private String formatInr(long paise) {
        return String.format("%d.%02d", paise / 100, paise % 100);
    }

    private TestInstruments.Behaviour behaviourOf(PaymentMethod pm) {
        try {
            return TestInstruments.Behaviour.valueOf(pm.getSimulatedBehaviour());
        } catch (Exception e) {
            return TestInstruments.Behaviour.ASYNC_SUCCESS;
        }
    }

    private String dump(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(String.format("%-14s %s%n", k, v)));
        return sb.toString();
    }

    private void sleep(int min, int max) {
        try {
            Thread.sleep(min + (long) (Math.random() * (max - min)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }
}
