package com.stripeclone.payment;

import java.util.Map;

/**
 * Stripe's published test card numbers and what each one does.
 *
 * <p>This is the simulated boundary. Everything behind it, the ledger and the state
 * machine, is real; this class stands in for the card network that would otherwise decide
 * whether an authorization succeeds.
 *
 * <p>Using Stripe's actual test numbers rather than inventing some means anyone who has
 * worked with Stripe already knows what 4242 4242 4242 4242 should do, and existing test
 * fixtures keep working.
 */
public final class TestCards {

    /** What the simulated network says about a card. */
    public enum Outcome {
        /** Authorization approved. The ledger then decides if the money is actually there. */
        APPROVED(null, null),

        DECLINED("card_declined", "generic_decline"),
        INSUFFICIENT_FUNDS("card_declined", "insufficient_funds"),
        LOST_CARD("card_declined", "lost_card"),
        STOLEN_CARD("card_declined", "stolen_card"),
        EXPIRED_CARD("expired_card", null),
        INCORRECT_CVC("incorrect_cvc", null),
        PROCESSING_ERROR("processing_error", null);

        private final String code;
        private final String declineCode;

        Outcome(String code, String declineCode) {
            this.code = code;
            this.declineCode = declineCode;
        }

        public boolean isApproved() {
            return this == APPROVED;
        }

        public String code() {
            return code;
        }

        public String declineCode() {
            return declineCode;
        }

        public String message() {
            return switch (this) {
                case APPROVED -> "Approved.";
                case INSUFFICIENT_FUNDS -> "Your card has insufficient funds.";
                case LOST_CARD, STOLEN_CARD, DECLINED -> "Your card was declined.";
                case EXPIRED_CARD -> "Your card has expired.";
                case INCORRECT_CVC -> "Your card's security code is incorrect.";
                case PROCESSING_ERROR -> "An error occurred while processing your card.";
            };
        }
    }

    private static final Map<String, Outcome> OUTCOMES = Map.ofEntries(
            Map.entry("4242424242424242", Outcome.APPROVED),
            Map.entry("4000056655665556", Outcome.APPROVED),   // visa debit
            Map.entry("5555555555554444", Outcome.APPROVED),   // mastercard
            Map.entry("378282246310005",  Outcome.APPROVED),   // amex
            Map.entry("6011111111111117", Outcome.APPROVED),   // discover

            Map.entry("4000000000000002", Outcome.DECLINED),
            Map.entry("4000000000009995", Outcome.INSUFFICIENT_FUNDS),
            Map.entry("4000000000009987", Outcome.LOST_CARD),
            Map.entry("4000000000009979", Outcome.STOLEN_CARD),
            Map.entry("4000000000000069", Outcome.EXPIRED_CARD),
            Map.entry("4000000000000127", Outcome.INCORRECT_CVC),
            Map.entry("4000000000000119", Outcome.PROCESSING_ERROR));

    private static final Map<String, String> BRANDS = Map.of(
            "4", "visa",
            "5", "mastercard",
            "34", "amex",
            "37", "amex",
            "6", "discover");

    private TestCards() {
    }

    /**
     * What the simulated network would say about this number.
     *
     * <p>An unrecognised number is declined rather than approved. Defaulting the other way
     * would mean a typo in a test silently passes.
     */
    public static Outcome outcomeFor(String cardNumber) {
        String digits = normalise(cardNumber);
        return OUTCOMES.getOrDefault(digits, Outcome.DECLINED);
    }

    public static boolean isKnown(String cardNumber) {
        return OUTCOMES.containsKey(normalise(cardNumber));
    }

    public static String brandOf(String cardNumber) {
        String digits = normalise(cardNumber);
        if (digits.length() < 2) {
            return "unknown";
        }
        String twoDigitPrefix = digits.substring(0, 2);
        if (BRANDS.containsKey(twoDigitPrefix)) {
            return BRANDS.get(twoDigitPrefix);
        }
        return BRANDS.getOrDefault(digits.substring(0, 1), "unknown");
    }

    public static String last4(String cardNumber) {
        String digits = normalise(cardNumber);
        if (digits.length() < 4) {
            throw new IllegalArgumentException("Card number is too short");
        }
        return digits.substring(digits.length() - 4);
    }

    /**
     * A stable identifier for a card, so the same card added twice can be recognised.
     *
     * <p>Stripe's real fingerprints are derived from the full number in a way that can't be
     * reversed. This one is just a hash, which is enough to make the behaviour observable
     * without pretending to be cryptographically interesting.
     */
    public static String fingerprint(String cardNumber) {
        String digits = normalise(cardNumber);
        return "fp_" + Integer.toHexString(digits.hashCode());
    }

    /**
     * Luhn check.
     *
     * <p>Every card number this system accepts is on the list above, so this adds nothing
     * to the decision. It's here because rejecting a malformed number before pretending to
     * contact a network is what a real integration does, and the test numbers all pass it.
     */
    public static boolean passesLuhn(String cardNumber) {
        String digits = normalise(cardNumber);
        if (digits.length() < 12 || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }

        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    private static String normalise(String cardNumber) {
        if (cardNumber == null) {
            return "";
        }
        return cardNumber.replaceAll("[\\s-]", "");
    }
}
