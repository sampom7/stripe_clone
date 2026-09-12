package com.stripeclone.api;

import com.stripeclone.api.dto.AttachRequest;
import com.stripeclone.api.dto.CreatePaymentMethodRequest;
import com.stripeclone.api.dto.PaymentMethodResponse;
import com.stripeclone.idempotency.IdempotencyService;
import com.stripeclone.payment.PaymentMethod;
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

@RestController
@RequestMapping("/v1/payment_methods")
public class PaymentMethodController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentMethodController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<PaymentMethodResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentMethodRequest request) {

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/payment_methods",
                request,
                PaymentMethodResponse.class,
                () -> {
                    PaymentMethod method = payments.createCard(
                            request.card().number(),
                            request.card().expMonth(),
                            request.card().expYear());
                    return IdempotencyService.Outcome.created(
                            PaymentMethodResponse.from(method), method.paymentMethodId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping("/{id}")
    public PaymentMethodResponse retrieve(@PathVariable String id) {
        return PaymentMethodResponse.from(payments.getPaymentMethod(id));
    }

    @GetMapping
    public StripeList<PaymentMethodResponse> list(@RequestParam String customer) {
        return StripeList.of(
                payments.paymentMethodsFor(customer).stream()
                        .map(PaymentMethodResponse::from)
                        .toList(),
                false,
                "/v1/payment_methods");
    }

    @PostMapping("/{id}/attach")
    public PaymentMethodResponse attach(
            @PathVariable String id,
            @Valid @RequestBody AttachRequest request) {
        return PaymentMethodResponse.from(payments.attach(id, request.customer()));
    }

    @PostMapping("/{id}/detach")
    public PaymentMethodResponse detach(@PathVariable String id) {
        return PaymentMethodResponse.from(payments.detach(id));
    }
}
