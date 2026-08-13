package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.Merchant;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.domain.Transaction;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Stands in for the acquirer host, the card network, and the issuer.
 *
 * <p>In production these are three separate parties across two network hops, and the gateway sees
 * only the first one. The round trip is: gateway to acquirer over a persistent TLS socket, acquirer
 * to network, network to issuer, and the same path back — typically well under a second end to end,
 * because the issuer is holding a socket open waiting and the whole protocol is designed around
 * fixed-width fields that need no parsing.
 *
 * <p>What this class reproduces faithfully is the <em>shape</em>: an 0100 authorisation request
 * carrying the amount and the instrument, a 0110 response carrying a two-digit verdict in DE 39,
 * and separately an 0220 advice at capture time that does not ask permission but states what
 * happened. Authorisation and clearing are genuinely different messages, sent at different times,
 * and conflating them is the single most common misunderstanding about card payments.
 */
@Component
public class CardNetworkSimulator {

    private static final DateTimeFormatter MMDDHHMMSS = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private final GatewayProperties props;
    private final Random random = new Random();

    public CardNetworkSimulator(GatewayProperties props) {
        this.props = props;
    }

    /**
     * MTI 0100 — authorisation request.
     *
     * <p>An approval here does not move any money. It asks the issuer to confirm the funds exist
     * and to place a hold on the cardholder's available credit. The hold decays on its own after
     * days to weeks depending on the merchant category, which is why an uncaptured authorisation is
     * a liability rather than an asset.
     */
    public RailResult authorize(PaymentIntent intent, PaymentMethod pm, Merchant merchant,
                                AcquirerRouter.Decision route, String cavv, String eci) {
        long start = System.currentTimeMillis();
        String stan = Ids.numeric(6);
        String rrn = Ids.numeric(12);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Iso8583Message request = new Iso8583Message("0100")
                // Only the masked PAN is shown. The real message carries the full number, which is
                // exactly why the link is encrypted and the gateway is in PCI scope.
                .set(2, maskedPan(pm))
                // 00 = purchase, from cardholder's default account, to default account.
                .set(3, "000000")
                .set(4, Iso8583Message.formatAmount(intent.getAmount()))
                .set(7, now.format(MMDDHHMMSS))
                .set(11, stan)
                .set(12, now.format(HHMMSS))
                .set(14, expiry(pm))
                .set(18, merchant.getMcc())
                // 81 = e-commerce, PAN keyed. The entry mode drives interchange and liability more
                // than almost any other field.
                .set(22, "81")
                .set(32, route.acquirer().id())
                .set(37, rrn)
                .set(41, "MOCKTRM1")
                .set(42, merchant.getId())
                .set(43, truncate(merchant.getName(), 22) + " " + merchant.getCountry())
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        if (pm.getNetworkToken() != null) {
            request.set(48, "TOKEN=" + pm.getNetworkToken() + ";TOKEN_ASSURANCE=HIGH");
        }
        if (cavv != null) {
            // 3DS proof travels in a private-use field. Its presence is what earns the liability
            // shift and, on many issuers, a materially better approval rate.
            request.set(126, "CAVV=" + cavv + ";ECI=" + eci + ";3DSVER=2.2.0");
        }

        simulateLatency();

        TestInstruments.Behaviour behaviour = behaviourOf(pm);
        String responseCode;
        String responseText;
        String declineCode;
        String authCode = null;
        Transaction.Outcome outcome;

        switch (behaviour) {
            case DECLINE_GENERIC -> {
                responseCode = "05";
                responseText = "Do not honour";
                declineCode = "card_declined";
                outcome = Transaction.Outcome.DECLINED;
            }
            case DECLINE_INSUFFICIENT_FUNDS -> {
                responseCode = "51";
                responseText = "Insufficient funds";
                declineCode = "insufficient_funds";
                outcome = Transaction.Outcome.DECLINED;
            }
            case DECLINE_EXPIRED_CARD -> {
                responseCode = "54";
                responseText = "Expired card";
                declineCode = "expired_card";
                outcome = Transaction.Outcome.DECLINED;
            }
            case DECLINE_INCORRECT_CVC -> {
                responseCode = "82";
                responseText = "Invalid CVV";
                declineCode = "incorrect_cvc";
                outcome = Transaction.Outcome.DECLINED;
            }
            case DECLINE_LOST_CARD -> {
                responseCode = "41";
                responseText = "Lost card, pick up";
                declineCode = "lost_card";
                outcome = Transaction.Outcome.DECLINED;
            }
            case DECLINE_STOLEN_CARD -> {
                responseCode = "43";
                responseText = "Stolen card, pick up";
                declineCode = "stolen_card";
                outcome = Transaction.Outcome.DECLINED;
            }
            case ISSUER_UNAVAILABLE -> {
                responseCode = "91";
                responseText = "Issuer or switch inoperative";
                declineCode = "issuer_unavailable";
                outcome = Transaction.Outcome.DECLINED;
            }
            default -> {
                responseCode = "00";
                responseText = "Approved or completed successfully";
                declineCode = null;
                authCode = Ids.random(6).toUpperCase();
                outcome = Transaction.Outcome.APPROVED;
            }
        }

        Iso8583Message response = new Iso8583Message("0110")
                .set(2, maskedPan(pm))
                .set(3, "000000")
                .set(4, Iso8583Message.formatAmount(intent.getAmount()))
                .set(7, now.format(MMDDHHMMSS))
                .set(11, stan)
                .set(37, rrn)
                .set(38, authCode)
                .set(39, responseCode)
                .set(41, "MOCKTRM1")
                .set(42, merchant.getId())
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        long latency = System.currentTimeMillis() - start;

        return new RailResult(outcome, responseCode, responseText, declineCode, authCode, rrn,
                "ntid_" + Ids.random(15), route.acquirer().id(),
                brandName(pm), latency,
                request.dump() + "\n-- routing --\n" + route.rationale(),
                response.dump(), false, null);
    }

    /**
     * MTI 0220 — capture advice.
     *
     * <p>An advice is not a request. The transaction has already happened as far as the merchant is
     * concerned; this message tells the network to include it in the clearing file so money can
     * actually move at settlement. That is why captures essentially never fail on the network: there
     * is nothing left to decline. What can fail is capturing more than was authorised, or capturing
     * after the hold has expired, and both are the gateway's job to prevent.
     */
    public RailResult capture(PaymentIntent intent, PaymentMethod pm, Merchant merchant, long amount) {
        long start = System.currentTimeMillis();
        String stan = Ids.numeric(6);
        String rrn = Ids.numeric(12);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Iso8583Message request = new Iso8583Message("0220")
                .set(2, maskedPan(pm))
                .set(3, "000000")
                .set(4, Iso8583Message.formatAmount(amount))
                .set(7, now.format(MMDDHHMMSS))
                .set(11, stan)
                .set(37, rrn)
                .set(38, intent.getAuthorizationCode())
                .set(42, merchant.getId())
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        simulateLatency();

        Iso8583Message response = new Iso8583Message("0230")
                .set(11, stan)
                .set(37, rrn)
                .set(39, "00")
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        return new RailResult(Transaction.Outcome.APPROVED, "00", "Capture accepted for clearing",
                null, intent.getAuthorizationCode(), rrn, intent.getNetworkTransactionId(),
                intent.getAcquirerId(), brandName(pm), System.currentTimeMillis() - start,
                request.dump(), response.dump(), false, null);
    }

    /**
     * MTI 0400 — reversal.
     *
     * <p>Used to release an authorisation the merchant will never capture. It is matched to the
     * original by STAN and RRN, which is why those fields are stored rather than discarded. Sending
     * a reversal is strictly better for everyone than letting the hold time out: the cardholder gets
     * their available balance back immediately instead of in a fortnight.
     */
    public RailResult voidAuthorization(PaymentIntent intent, PaymentMethod pm, Merchant merchant) {
        long start = System.currentTimeMillis();
        String stan = Ids.numeric(6);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Iso8583Message request = new Iso8583Message("0400")
                .set(2, maskedPan(pm))
                .set(3, "000000")
                .set(4, Iso8583Message.formatAmount(intent.getAmountCapturable()))
                .set(7, now.format(MMDDHHMMSS))
                .set(11, stan)
                .set(38, intent.getAuthorizationCode())
                .set(42, merchant.getId())
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        simulateLatency();

        Iso8583Message response = new Iso8583Message("0410").set(11, stan).set(39, "00");

        return new RailResult(Transaction.Outcome.APPROVED, "00", "Reversal accepted", null,
                intent.getAuthorizationCode(), Ids.numeric(12), intent.getNetworkTransactionId(),
                intent.getAcquirerId(), brandName(pm), System.currentTimeMillis() - start,
                request.dump(), response.dump(), false, null);
    }

    /**
     * Refund, sent as a 0220 with processing code 20 (credit).
     *
     * <p>Note that it carries its own RRN. It is a new transaction in the clearing file travelling
     * merchant-to-cardholder, not an undo of the original — which is why it settles on its own
     * schedule and why the interchange on the original sale is generally not returned.
     */
    public RailResult refund(PaymentIntent intent, PaymentMethod pm, Merchant merchant, long amount) {
        long start = System.currentTimeMillis();
        String stan = Ids.numeric(6);
        String rrn = Ids.numeric(12);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        Iso8583Message request = new Iso8583Message("0220")
                .set(2, maskedPan(pm))
                // 20 = credit/refund, as opposed to 00 = purchase.
                .set(3, "200000")
                .set(4, Iso8583Message.formatAmount(amount))
                .set(7, now.format(MMDDHHMMSS))
                .set(11, stan)
                .set(37, rrn)
                .set(42, merchant.getId())
                .set(48, "ORIGINAL_RRN=" + safe(intent.getNetworkTransactionId()))
                .set(49, Iso8583Message.numericCurrency(intent.getCurrency()));

        simulateLatency();

        Iso8583Message response = new Iso8583Message("0230").set(11, stan).set(37, rrn).set(39, "00");

        return new RailResult(Transaction.Outcome.APPROVED, "00", "Refund accepted for clearing",
                null, null, rrn, intent.getNetworkTransactionId(), intent.getAcquirerId(),
                brandName(pm), System.currentTimeMillis() - start,
                request.dump(), response.dump(), false, null);
    }

    private TestInstruments.Behaviour behaviourOf(PaymentMethod pm) {
        try {
            return TestInstruments.Behaviour.valueOf(pm.getSimulatedBehaviour());
        } catch (Exception e) {
            return TestInstruments.Behaviour.APPROVE;
        }
    }

    private void simulateLatency() {
        long min = props.getRail().getMinLatencyMs();
        long max = Math.max(min + 1, props.getRail().getMaxLatencyMs());
        try {
            Thread.sleep(min + random.nextInt((int) (max - min)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskedPan(PaymentMethod pm) {
        String bin = pm.getCardBin() == null ? "000000" : pm.getCardBin();
        String last4 = pm.getCardLast4() == null ? "0000" : pm.getCardLast4();
        return bin + "******" + last4;
    }

    private String expiry(PaymentMethod pm) {
        if (pm.getCardExpYear() == null || pm.getCardExpMonth() == null) {
            return null;
        }
        return String.format("%02d%02d", pm.getCardExpYear() % 100, pm.getCardExpMonth());
    }

    private String brandName(PaymentMethod pm) {
        return pm.getCardBrand() == null ? "card" : pm.getCardBrand();
    }

    private String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
