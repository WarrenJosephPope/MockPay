package dev.mockpay.gateway.support;

import java.security.SecureRandom;

/**
 * Prefixed, opaque, non-sequential resource identifiers.
 *
 * <p>Real gateways never expose auto-increment primary keys. A sequential id leaks volume
 * ("we did 40,000 payments this month"), invites enumeration of other merchants' objects, and
 * makes it impossible to tell at a glance what kind of object you are holding. A type prefix
 * turns a support ticket that says {@code re_8Fk2...} into an instantly recognisable refund.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private Ids() {
    }

    public static String generate(String prefix) {
        return prefix + "_" + random(24);
    }

    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Numeric strings, for ISO 8583 fields that are defined as fixed-length digits. */
    public static String numeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return sb.toString();
    }
}
