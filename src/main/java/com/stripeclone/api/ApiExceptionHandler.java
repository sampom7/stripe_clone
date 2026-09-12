package com.stripeclone.api;

import com.stripeclone.idempotency.ConcurrentRequestException;
import com.stripeclone.idempotency.IdempotencyConflictException;
import com.stripeclone.ledger.AccountNotFoundException;
import com.stripeclone.ledger.InsufficientFundsException;
import com.stripeclone.ledger.UnbalancedTransactionException;
import com.stripeclone.money.CurrencyMismatchException;
import com.stripeclone.payment.ChargeNotFoundException;
import com.stripeclone.payment.CustomerNotFoundException;
import com.stripeclone.payment.InvalidStateTransitionException;
import com.stripeclone.payment.PaymentException;
import com.stripeclone.payment.PaymentIntentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Turns exceptions into Stripe-shaped error responses.
 *
 * <p>Controllers deliberately don't catch anything. The first version of this project
 * wrapped every handler body in a try/catch that returned a 500, which meant the advice
 * class never ran and a missing resource looked identical to a database outage. Letting
 * exceptions propagate is what makes one place responsible for the mapping.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({PaymentIntentNotFoundException.class, ChargeNotFoundException.class,
            CustomerNotFoundException.class, AccountNotFoundException.class})
    public ResponseEntity<StripeError> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(StripeError.invalidRequest("resource_missing", e.getMessage(), "id"));
    }

    /**
     * A declined payment is a card error, not a server error.
     *
     * <p>Stripe reports a failed charge as HTTP 402 with a decline code, which is what a
     * client needs in order to tell "your card was refused" apart from "we broke".
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<StripeError> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(StripeError.cardError(
                        "card_declined",
                        "Your card has insufficient funds.",
                        "insufficient_funds"));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<StripeError> handleBadTransition(InvalidStateTransitionException e) {
        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest(
                        "payment_intent_unexpected_state", e.getMessage(), "payment_intent"));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<StripeError> handlePayment(PaymentException e) {
        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest(e.code(), e.getMessage(), null));
    }

    /**
     * Reusing a key with a different body.
     *
     * <p>422 rather than 400, matching Stripe: the request is well-formed, it just can't be
     * honoured given what that key was used for before.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<StripeError> handleIdempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.unprocessableEntity()
                .body(StripeError.idempotencyError(e.getMessage()));
    }

    /** An earlier request with the same key is still running. Retry shortly. */
    @ExceptionHandler(ConcurrentRequestException.class)
    public ResponseEntity<StripeError> handleConcurrent(ConcurrentRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(StripeError.idempotencyError(e.getMessage()));
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<StripeError> handleCurrencyMismatch(CurrencyMismatchException e) {
        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest(e.getMessage(), "currency"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StripeError> handleValidation(MethodArgumentNotValidException e) {
        List<String> messages = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() == null
                        ? error.getField() + " is invalid"
                        : error.getDefaultMessage())
                .toList();

        String param = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField())
                .orElse(null);

        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest(
                        "parameter_invalid", String.join("; ", messages), param));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<StripeError> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest(
                        "parameter_invalid", "Request body could not be parsed as JSON", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StripeError> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(StripeError.invalidRequest("parameter_invalid", e.getMessage(), null));
    }

    /**
     * An unbalanced transaction reaching this layer is a bug, not bad input.
     *
     * <p>Logged loudly on purpose. It means something built a transaction that doesn't sum
     * to zero and the domain check was the only thing that caught it.
     */
    @ExceptionHandler(UnbalancedTransactionException.class)
    public ResponseEntity<StripeError> handleUnbalanced(UnbalancedTransactionException e) {
        log.error("Unbalanced transaction reached the API layer", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(StripeError.apiError("An internal accounting error occurred."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StripeError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(StripeError.apiError("An unexpected error occurred."));
    }
}
