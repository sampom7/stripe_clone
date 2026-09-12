package com.stripeclone.api;

import com.stripeclone.api.dto.CancelRequest;
import com.stripeclone.api.dto.CaptureRequest;
import com.stripeclone.api.dto.ConfirmRequest;
import com.stripeclone.api.dto.ChargeResponse;
import com.stripeclone.api.dto.CreatePaymentIntentRequest;
import com.stripeclone.api.dto.PaymentIntentResponse;
import com.stripeclone.idempotency.IdempotencyService;
import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;
import com.stripeclone.payment.CaptureMethod;
import com.stripeclone.payment.Customer;
import com.stripeclone.payment.CustomerNotFoundException;
import com.stripeclone.payment.PaymentIntent;
import com.stripeclone.payment.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payment intent endpoints.
 *
 * <p>No try/catch anywhere in here. Exceptions go to {@link ApiExceptionHandler}, which is
 * the only place that knows how to turn a failure into an HTTP status.
 */
@RestController
@RequestMapping("/v1/payment_intents")
public class PaymentIntentController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentIntentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request) {

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/payment_intents",
                request,
                PaymentIntentResponse.class,
                () -> {
                    Currency currency = Currency.of(request.currency());
                    String customerAccount = resolveCustomerAccount(request.customer());

                    PaymentIntent intent = payments.createIntent(
                            new PaymentService.CreateIntentRequest(
                                    request.customer(),
                                    customerAccount,
                                    request.merchantAccount(),
                                    Amount.of(request.amount(), currency),
                                    CaptureMethod.fromWire(request.captureMethod()),
                                    request.description()));

                    return IdempotencyService.Outcome.created(
                            PaymentIntentResponse.from(intent), intent.paymentIntentId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping("/{id}")
    public PaymentIntentResponse retrieve(@PathVariable String id) {
        return PaymentIntentResponse.from(payments.getIntent(id));
    }

    @GetMapping
    public StripeList<PaymentIntentResponse> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter) {

        int effectiveLimit = clampLimit(limit);

        // Fetch one extra row to decide has_more without a second count query.
        List<PaymentIntent> page = payments.listIntents(effectiveLimit + 1, startingAfter);
        boolean hasMore = page.size() > effectiveLimit;
        List<PaymentIntent> visible = hasMore ? page.subList(0, effectiveLimit) : page;

        return StripeList.of(
                visible.stream().map(PaymentIntentResponse::from).toList(),
                hasMore,
                "/v1/payment_intents");
    }

    /**
     * Confirms an intent.
     *
     * <p>With a payment method and card number in the body, the card goes past the
     * simulated network first and can be declined there. Without one, the intent is
     * confirmed directly and only the ledger can refuse it.
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ConfirmRequest request) {

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/payment_intents/" + id + "/confirm",
                request == null ? id : request,
                PaymentIntentResponse.class,
                () -> {
                    PaymentIntent intent = (request != null && request.paymentMethod() != null)
                            ? payments.confirmWithCard(
                                    id, request.paymentMethod(), request.cardNumber())
                            : payments.confirm(id);
                    return IdempotencyService.Outcome.ok(
                            PaymentIntentResponse.from(intent), intent.paymentIntentId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentIntentResponse> capture(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureRequest request) {

        PaymentIntent current = payments.getIntent(id);
        Amount amount = (request == null || request.amountToCapture() == null)
                ? null
                : Amount.of(request.amountToCapture(), current.currency());

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/payment_intents/" + id + "/capture",
                request,
                PaymentIntentResponse.class,
                () -> {
                    PaymentIntent intent = payments.capture(id, amount);
                    return IdempotencyService.Outcome.ok(
                            PaymentIntentResponse.from(intent), intent.paymentIntentId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CancelRequest request) {

        String reason = request == null ? null : request.cancellationReason();

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/payment_intents/" + id + "/cancel",
                request,
                PaymentIntentResponse.class,
                () -> {
                    PaymentIntent intent = payments.cancel(id, reason);
                    return IdempotencyService.Outcome.ok(
                            PaymentIntentResponse.from(intent), intent.paymentIntentId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    /** The charges a given intent produced. Partial captures make more than one. */
    @GetMapping("/{id}/charges")
    public StripeList<ChargeResponse> charges(@PathVariable String id) {
        payments.getIntent(id);      // 404 if it doesn't exist
        return StripeList.of(
                payments.chargesFor(id).stream().map(ChargeResponse::from).toList(),
                false,
                "/v1/payment_intents/" + id + "/charges");
    }

    private String resolveCustomerAccount(String customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customer is required");
        }
        Customer customer = payments.findCustomer(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        return customer.accountId();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
