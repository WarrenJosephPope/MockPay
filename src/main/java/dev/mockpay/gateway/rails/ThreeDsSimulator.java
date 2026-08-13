package dev.mockpay.gateway.rails;

import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EMV 3-D Secure 2.x, the authentication step that runs <em>before</em> authorisation.
 *
 * <p>The three domains the name refers to are the acquirer's, the issuer's, and the interoperability
 * domain between them. The message flow is AReq to the directory server and on to the issuer's
 * access control server, then ARes back. If the issuer is satisfied by the risk data alone the
 * result is <b>frictionless</b> — the customer sees nothing at all, which is the overwhelming
 * majority of 3DS2 traffic. If not, a <b>challenge</b> follows: CReq and CRes carry an OTP or a
 * biometric prompt inside an iframe, and RReq/RRes deliver the final result out of band.
 *
 * <p>Version 1 of the protocol was a full-page redirect that destroyed conversion. Version 2 exists
 * because regulators (PSD2 in Europe, RBI's additional-factor rules in India) made authentication
 * mandatory, and the industry needed it to stop costing sales. The commercial payoff is the
 * <b>liability shift</b>: once the issuer authenticates a payment, a later fraud chargeback lands on
 * them rather than the merchant.
 */
@Component
public class ThreeDsSimulator {

    /** The value carried in DE 126 that proves authentication happened. */
    public record AuthenticationResult(
            String transStatus,
            String eci,
            String cavv,
            String dsTransId,
            boolean challengeRequired,
            String reason,
            Map<String, Object> areq,
            Map<String, Object> ares) {
    }

    /**
     * The AReq/ARes exchange.
     *
     * <p>The ~150 data elements sent here are the entire point of 3DS2: browser fingerprint, device
     * details, shipping address, account age, prior transaction history. The issuer scores them and
     * usually concludes it does not need to bother the customer.
     */
    public AuthenticationResult authenticate(PaymentIntent intent, PaymentMethod pm,
                                             boolean riskEngineWantsChallenge, String browserInfo) {
        String dsTransId = java.util.UUID.randomUUID().toString();

        Map<String, Object> areq = new LinkedHashMap<>();
        areq.put("messageType", "AReq");
        areq.put("messageVersion", "2.2.0");
        areq.put("threeDSServerTransID", java.util.UUID.randomUUID().toString());
        areq.put("acctNumber", "***" + safe(pm.getCardLast4()));
        areq.put("purchaseAmount", String.valueOf(intent.getAmount()));
        areq.put("purchaseCurrency", Iso8583Message.numericCurrency(intent.getCurrency()));
        areq.put("messageCategory", "01"); // 01 = payment authentication
        areq.put("deviceChannel", "02");   // 02 = browser
        areq.put("browserInfo", browserInfo == null ? "unavailable" : browserInfo);
        // Merchants can ask for an outcome, but the issuer decides. Requesting "no challenge"
        // is a hint backed by an SCA exemption claim, not an instruction.
        areq.put("threeDSRequestorChallengeInd", riskEngineWantsChallenge ? "04" : "01");

        TestInstruments.Behaviour behaviour = behaviourOf(pm);
        boolean challenge = riskEngineWantsChallenge
                || behaviour == TestInstruments.Behaviour.THREE_DS_REQUIRED_SUCCESS
                || behaviour == TestInstruments.Behaviour.THREE_DS_REQUIRED_FAIL;

        Map<String, Object> ares = new LinkedHashMap<>();
        ares.put("messageType", "ARes");
        ares.put("messageVersion", "2.2.0");
        ares.put("dsTransID", dsTransId);

        if (challenge) {
            // "C" means: I am not satisfied, send the cardholder to me.
            ares.put("transStatus", "C");
            ares.put("acsURL", "(gateway-hosted challenge page)");
            return new AuthenticationResult("C", null, null, dsTransId, true,
                    riskEngineWantsChallenge
                            ? "Step-up requested by the gateway's own risk engine to obtain liability shift"
                            : "Issuer requires a challenge for this instrument",
                    areq, ares);
        }

        // "Y" plus a CAVV and ECI 05 is a fully authenticated, liability-shifted transaction.
        String cavv = Base64.getEncoder().encodeToString(Ids.random(20).getBytes());
        ares.put("transStatus", "Y");
        ares.put("eci", "05");
        ares.put("authenticationValue", cavv);
        return new AuthenticationResult("Y", "05", cavv, dsTransId, false,
                "Frictionless: issuer accepted the risk data without challenging the cardholder",
                areq, ares);
    }

    /**
     * The CReq/CRes exchange — the customer has typed something into the challenge iframe.
     *
     * <p>Three attempts is the conventional limit before the ACS abandons the authentication.
     */
    public AuthenticationResult completeChallenge(PaymentMethod pm, boolean otpCorrect) {
        String dsTransId = java.util.UUID.randomUUID().toString();
        Map<String, Object> creq = new LinkedHashMap<>();
        creq.put("messageType", "CReq");
        creq.put("challengeDataEntry", otpCorrect ? "<redacted OTP>" : "<incorrect OTP>");

        Map<String, Object> cres = new LinkedHashMap<>();
        cres.put("messageType", "CRes");

        boolean pass = otpCorrect && behaviourOf(pm) != TestInstruments.Behaviour.THREE_DS_REQUIRED_FAIL;

        if (pass) {
            String cavv = Base64.getEncoder().encodeToString(Ids.random(20).getBytes());
            cres.put("transStatus", "Y");
            cres.put("eci", "05");
            return new AuthenticationResult("Y", "05", cavv, dsTransId, false,
                    "Cardholder authenticated; fraud liability now sits with the issuer", creq, cres);
        }

        // "N" means authentication failed. Authorising anyway is legal in some markets but you keep
        // the fraud liability and issuers decline these at a much higher rate.
        cres.put("transStatus", "N");
        return new AuthenticationResult("N", "07", null, dsTransId, false,
                "Authentication failed; no liability shift", creq, cres);
    }

    private TestInstruments.Behaviour behaviourOf(PaymentMethod pm) {
        try {
            return TestInstruments.Behaviour.valueOf(pm.getSimulatedBehaviour());
        } catch (Exception e) {
            return TestInstruments.Behaviour.APPROVE;
        }
    }

    private String safe(String s) {
        return s == null ? "0000" : s;
    }
}
