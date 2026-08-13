package dev.mockpay.gateway.rails;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A readable stand-in for an ISO 8583 message.
 *
 * <p>Real card authorisation messages are not JSON. They are a 4-digit message type indicator, a
 * 64-bit bitmap saying which of 128 numbered data elements are present, and then those elements
 * packed end to end with no field names anywhere. Field 4 is the amount because the spec says so,
 * and if you put it in field 5 the issuer reads garbage.
 *
 * <p>This class keeps the numbering and the semantics — DE 2 really is the PAN, DE 39 really is the
 * response code — but renders them as text you can read in a log, and computes the real bitmap so
 * you can see how presence is signalled. The point is to make the format legible, not to be
 * wire-compatible with Visa.
 *
 * <p>The modern successor is ISO 20022, which is XML with named fields and is progressively
 * replacing 8583 in new schemes and instant-payment rails.
 */
public class Iso8583Message {

    /** Canonical names for the data elements this simulator uses. */
    private static final Map<Integer, String> DE_NAMES = Map.ofEntries(
            Map.entry(2, "Primary Account Number (PAN)"),
            Map.entry(3, "Processing Code"),
            Map.entry(4, "Amount, Transaction"),
            Map.entry(7, "Transmission Date & Time"),
            Map.entry(11, "System Trace Audit Number (STAN)"),
            Map.entry(12, "Local Transaction Time"),
            Map.entry(14, "Expiration Date"),
            Map.entry(18, "Merchant Category Code"),
            Map.entry(22, "Point of Service Entry Mode"),
            Map.entry(32, "Acquiring Institution ID"),
            Map.entry(37, "Retrieval Reference Number (RRN)"),
            Map.entry(38, "Authorization ID Response"),
            Map.entry(39, "Response Code"),
            Map.entry(41, "Card Acceptor Terminal ID"),
            Map.entry(42, "Card Acceptor ID (Merchant ID)"),
            Map.entry(43, "Card Acceptor Name/Location"),
            Map.entry(48, "Additional Data - Private"),
            Map.entry(49, "Currency Code, Transaction"),
            Map.entry(55, "ICC Data (EMV chip)"),
            Map.entry(126, "Private Use - 3DS/CAVV"));

    private final String mti;
    private final Map<Integer, String> fields = new TreeMap<>();

    public Iso8583Message(String mti) {
        this.mti = mti;
    }

    public Iso8583Message set(int de, String value) {
        if (value != null) {
            fields.put(de, value);
        }
        return this;
    }

    public String getMti() {
        return mti;
    }

    public String get(int de) {
        return fields.get(de);
    }

    /**
     * The primary bitmap: 64 bits, one per data element 1-64, rendered as 16 hex characters.
     *
     * <p>Bit 1 is a flag meaning "a secondary bitmap follows", which is how the format addresses
     * elements 65-128 without paying for them on every message.
     */
    public String primaryBitmapHex() {
        long bitmap = 0L;
        boolean needsSecondary = fields.keySet().stream().anyMatch(de -> de > 64);
        if (needsSecondary) {
            bitmap |= 1L << 63;
        }
        for (int de : fields.keySet()) {
            if (de >= 2 && de <= 64) {
                bitmap |= 1L << (64 - de);
            }
        }
        return String.format("%016X", bitmap);
    }

    public String secondaryBitmapHex() {
        long bitmap = 0L;
        boolean any = false;
        for (int de : fields.keySet()) {
            if (de >= 65 && de <= 128) {
                bitmap |= 1L << (128 - de);
                any = true;
            }
        }
        return any ? String.format("%016X", bitmap) : null;
    }

    /** What the MTI's four digits actually mean. */
    public String describeMti() {
        if (mti == null || mti.length() != 4) {
            return "unknown";
        }
        String version = switch (mti.charAt(0)) {
            case '0' -> "ISO 8583:1987";
            case '1' -> "ISO 8583:1993";
            case '2' -> "ISO 8583:2003";
            default -> "national/private";
        };
        String messageClass = switch (mti.charAt(1)) {
            case '1' -> "Authorization";
            case '2' -> "Financial";
            case '3' -> "File action";
            case '4' -> "Reversal/Chargeback";
            case '5' -> "Reconciliation";
            case '6' -> "Administrative";
            case '7' -> "Fee collection";
            case '8' -> "Network management";
            default -> "reserved";
        };
        String function = switch (mti.charAt(2)) {
            case '0' -> "Request";
            case '1' -> "Response";
            case '2' -> "Advice";
            case '3' -> "Advice response";
            case '4' -> "Notification";
            default -> "reserved";
        };
        String origin = switch (mti.charAt(3)) {
            case '0' -> "Acquirer";
            case '1' -> "Acquirer repeat";
            case '2' -> "Issuer";
            case '3' -> "Issuer repeat";
            default -> "Other";
        };
        return version + " / " + messageClass + " / " + function + " / from " + origin;
    }

    /** Human-readable dump, stored on the Transaction row so you can inspect it later. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("MTI  ").append(mti).append("   (").append(describeMti()).append(")\n");
        sb.append("BMP1 ").append(primaryBitmapHex()).append("\n");
        String secondary = secondaryBitmapHex();
        if (secondary != null) {
            sb.append("BMP2 ").append(secondary).append("\n");
        }
        for (Map.Entry<Integer, String> e : fields.entrySet()) {
            sb.append(String.format("DE%-4d %-34s %s%n",
                    e.getKey(),
                    DE_NAMES.getOrDefault(e.getKey(), "(reserved)"),
                    e.getValue()));
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mti", mti);
        map.put("mti_meaning", describeMti());
        map.put("bitmap", primaryBitmapHex());
        Map<String, String> de = new LinkedHashMap<>();
        fields.forEach((k, v) -> de.put("DE" + k + " " + DE_NAMES.getOrDefault(k, ""), v));
        map.put("data_elements", de);
        return map;
    }

    /**
     * DE 4 is a fixed 12-digit numeric field with no decimal point. The exponent lives in DE 49
     * (the currency code) — the message itself carries no notion of "cents".
     */
    public static String formatAmount(long minorUnits) {
        return String.format("%012d", minorUnits);
    }

    /** ISO 4217 numeric currency codes. DE 49 is numeric, not the three-letter alphabetic code. */
    public static String numericCurrency(String alpha) {
        return switch (alpha.toUpperCase()) {
            case "USD" -> "840";
            case "EUR" -> "978";
            case "GBP" -> "826";
            case "INR" -> "356";
            case "JPY" -> "392";
            case "AED" -> "784";
            case "SGD" -> "702";
            case "AUD" -> "036";
            case "CAD" -> "124";
            default -> "999";
        };
    }
}
