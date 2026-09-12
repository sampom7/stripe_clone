package com.stripeclone.payment;

import java.util.Map;
import java.util.Set;

import static com.stripeclone.payment.PaymentIntentStatus.CANCELED;
import static com.stripeclone.payment.PaymentIntentStatus.PROCESSING;
import static com.stripeclone.payment.PaymentIntentStatus.REQUIRES_CAPTURE;
import static com.stripeclone.payment.PaymentIntentStatus.REQUIRES_CONFIRMATION;
import static com.stripeclone.payment.PaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.stripeclone.payment.PaymentIntentStatus.SUCCEEDED;

/**
 * Which status changes are allowed.
 *
 * <p>The whole table is here in one place rather than spread across the service as
 * scattered status checks. When every transition is visible at once it's obvious what's
 * reachable and what isn't, and adding a status means editing one map instead of hunting
 * for every {@code if} that needs a new branch.
 *
 * <pre>
 *   requires_payment_method
 *           |  attach
 *           v
 *   requires_confirmation
 *           |  confirm
 *           +------------------> requires_capture   (manual capture)
 *           |                           |  capture
 *           |                           v
 *           +------------------>     succeeded      (automatic capture)
 *
 *   anything not yet terminal ---------> canceled
 * </pre>
 */
public final class PaymentIntentStateMachine {

    private static final Map<PaymentIntentStatus, Set<PaymentIntentStatus>> ALLOWED = Map.of(
            REQUIRES_PAYMENT_METHOD, Set.of(REQUIRES_CONFIRMATION, CANCELED),
            REQUIRES_CONFIRMATION,   Set.of(REQUIRES_CAPTURE, PROCESSING, SUCCEEDED,
                                            REQUIRES_PAYMENT_METHOD, CANCELED),
            REQUIRES_CAPTURE,        Set.of(SUCCEEDED, PROCESSING, CANCELED),
            PROCESSING,              Set.of(SUCCEEDED, REQUIRES_PAYMENT_METHOD, CANCELED),
            SUCCEEDED,               Set.of(),
            CANCELED,                Set.of());

    private PaymentIntentStateMachine() {
    }

    public static boolean canTransition(PaymentIntentStatus from, PaymentIntentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * @throws InvalidStateTransitionException if the move isn't allowed
     */
    public static void assertTransition(PaymentIntentStatus from, PaymentIntentStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }

    public static Set<PaymentIntentStatus> allowedFrom(PaymentIntentStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
