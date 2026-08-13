package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.rails.TestInstruments;
import dev.mockpay.gateway.repo.PaymentMethodRepository;
import dev.mockpay.gateway.support.Crypto;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * Turns a card number into something safe to keep.
 *
 * <p>Tokenisation is what lets the rest of the system exist outside PCI scope. The PAN arrives here,
 * is used to derive everything that will ever be needed from it — brand, BIN, last four, a
 * fingerprint — and is then dropped. Nothing downstream ever sees it again, so nothing downstream
 * is subject to an audit.
 *
 * <p>Two distinct things are called "tokens" in this industry and conflating them causes real
 * confusion:
 *
 * <ul>
 *   <li>A <b>gateway token</b> ({@code pm_...}) is a reference into this gateway's own vault. It is
 *       worthless to anyone else, which is also its limitation: it locks the merchant in, because it
 *       cannot be handed to another processor.
 *   <li>A <b>network token</b> is issued by Visa or Mastercard themselves. It is a real, usable
 *       16-digit surrogate PAN, tied to a specific merchant, that the network detokenises at
 *       authorisation time. Because the network keeps it synchronised with the underlying card, a
 *       reissued card keeps working without the customer updating anything — and issuers approve
 *       network-tokenised traffic at measurably higher rates.
 * </ul>
 *
 * <p>The CVV is used for the authorisation attempt and then discarded. Storing it after
 * authorisation is prohibited outright by PCI DSS, with no exception for "we encrypt it".
 */
@Service
public class TokenizationService {

    /** Stands in for a per-installation secret from a key management service. */
    private static final String FINGERPRINT_SALT = "mockpay-fingerprint-salt-v1";

    private final PaymentMethodRepository paymentMethods;

    public TokenizationService(PaymentMethodRepository paymentMethods) {
        this.paymentMethods = paymentMethods;
    }

    public record CardInput(String number, Integer expMonth, Integer expYear, String cvc) {
    }

    public PaymentMethod tokenizeCard(String merchantId, CardInput input) {
        String pan = input.number() == null ? "" : input.number().replaceAll("[^0-9]", "");

        if (!TestInstruments.luhnValid(pan)) {
            throw new ApiException(400, "invalid_request_error", "incorrect_number",
                    "The card number failed its checksum. Check for a mistyped digit.");
        }
        if (input.expMonth() == null || input.expMonth() < 1 || input.expMonth() > 12) {
            throw new ApiException(400, "invalid_request_error", "invalid_expiry_month",
                    "Expiry month must be between 1 and 12.");
        }
        if (input.expYear() == null) {
            throw new ApiException(400, "invalid_request_error", "invalid_expiry_year",
                    "Expiry year is required.");
        }
        // Rejecting an expired card locally saves a pointless network round trip and an avoidable
        // decline on the issuer's records.
        YearMonth expiry = YearMonth.of(normaliseYear(input.expYear()), input.expMonth());
        if (expiry.isBefore(YearMonth.now())) {
            throw new ApiException(400, "invalid_request_error", "expired_card",
                    "This card expired in " + expiry + ".");
        }
        if (input.cvc() != null && !input.cvc().matches("\\d{3,4}")) {
            throw new ApiException(400, "invalid_request_error", "incorrect_cvc",
                    "The security code must be 3 digits (4 for American Express).");
        }

        TestInstruments.CardProfile profile = TestInstruments.lookupCard(pan);

        // Everything the system will ever need is extracted here, while the PAN is still in hand.
        String last4 = pan.substring(pan.length() - 4);
        String bin = pan.substring(0, Math.min(6, pan.length()));
        String fingerprint = Crypto.sha256Hex(FINGERPRINT_SALT + pan).substring(0, 32);
        String networkToken = provisionNetworkToken(pan, merchantId);

        PaymentMethod pm = PaymentMethod.card(
                Ids.generate("pm"), merchantId, profile.brand(), last4,
                input.expMonth(), normaliseYear(input.expYear()), profile.funding(),
                profile.issuer(), profile.country(), bin, fingerprint, networkToken,
                profile.behaviour().name());

        // From this point the PAN and CVC exist only as local variables about to go out of scope.
        return paymentMethods.save(pm);
    }

    public PaymentMethod tokenizeUpi(String merchantId, String vpa) {
        if (vpa == null || !vpa.matches("^[\\w.\\-]{2,50}@[\\w.\\-]{2,30}$")) {
            throw new ApiException(400, "invalid_request_error", "invalid_vpa",
                    "A UPI ID looks like name@bank.");
        }
        TestInstruments.Behaviour behaviour = TestInstruments.lookupVpa(vpa);
        return paymentMethods.save(
                PaymentMethod.upi(Ids.generate("pm"), merchantId, vpa.toLowerCase(), behaviour.name()));
    }

    public PaymentMethod tokenizeWallet(String merchantId, String provider) {
        if (provider == null || provider.isBlank()) {
            throw new ApiException(400, "invalid_request_error", "invalid_wallet",
                    "A wallet provider is required.");
        }
        // Pass-through wallets (Apple Pay, Google Pay) would arrive with a network token and a
        // cryptogram already attached, and would be processed as a card from here on.
        String behaviour = provider.toLowerCase().contains("fail")
                ? TestInstruments.Behaviour.ASYNC_FAILURE.name()
                : TestInstruments.Behaviour.ASYNC_SUCCESS.name();
        return paymentMethods.save(
                PaymentMethod.wallet(Ids.generate("pm"), merchantId, provider.toLowerCase(), behaviour));
    }

    /**
     * Stands in for a call to Visa Token Service or Mastercard MDES.
     *
     * <p>A real network token is itself a valid, Luhn-passing 16-digit number in a dedicated BIN
     * range, scoped to one merchant, and useless if stolen from a different one.
     */
    private String provisionNetworkToken(String pan, String merchantId) {
        String derived = Crypto.sha256Hex(pan + "|" + merchantId).replaceAll("[^0-9]", "");
        String body = (derived + "0000000000000000").substring(0, 11);
        String partial = "49" + body;
        return partial + luhnCheckDigit(partial);
    }

    private char luhnCheckDigit(String partial) {
        int sum = 0;
        boolean doubling = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }
        return (char) ('0' + ((10 - (sum % 10)) % 10));
    }

    /** Accepts both {@code 27} and {@code 2027}, as every checkout form in the world must. */
    private int normaliseYear(int year) {
        return year < 100 ? 2000 + year : year;
    }
}
