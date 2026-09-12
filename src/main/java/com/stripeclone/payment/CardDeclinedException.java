package com.stripeclone.payment;

/**
 * The simulated card network refused the authorization.
 *
 * <p>Distinct from InsufficientFundsException, which is the ledger saying the money isn't
 * there. This one is the network saying no before the ledger is ever consulted.
 */
public class CardDeclinedException extends RuntimeException {

    private final String code;
    private final String declineCode;

    public CardDeclinedException(TestCards.Outcome outcome) {
        super(outcome.message());
        this.code = outcome.code();
        this.declineCode = outcome.declineCode();
    }

    public String code() {
        return code;
    }

    public String declineCode() {
        return declineCode;
    }
}
