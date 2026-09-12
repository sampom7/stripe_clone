package com.stripeclone.common;

import java.security.SecureRandom;

/**
 * Prefixed identifiers in Stripe's style: {@code pi_}, {@code ch_}, {@code txn_}.
 *
 * <p>The prefix makes an id self-describing in a log line or a bug report, which is worth
 * more than it sounds when debugging a flow that spans several object types.
 */
public final class Ids {

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String generate(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 1 + DEFAULT_LENGTH);
        sb.append(prefix).append('_');
        for (int i = 0; i < DEFAULT_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String account()     { return generate("acct"); }
    public static String transaction() { return generate("txn"); }
    public static String paymentIntent() { return generate("pi"); }
    public static String charge()      { return generate("ch"); }
    public static String refund()      { return generate("re"); }
    public static String customer()    { return generate("cus"); }
    public static String paymentMethod() { return generate("pm"); }
    public static String event()       { return generate("evt"); }
}
