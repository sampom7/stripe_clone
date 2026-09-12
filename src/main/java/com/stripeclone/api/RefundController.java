package com.stripeclone.api;

import com.stripeclone.api.dto.CreateRefundRequest;
import com.stripeclone.api.dto.RefundResponse;
import com.stripeclone.idempotency.IdempotencyService;
import com.stripeclone.money.Amount;
import com.stripeclone.payment.Charge;
import com.stripeclone.payment.PaymentService;
import com.stripeclone.payment.Refund;
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
@RequestMapping("/v1/refunds")
public class RefundController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public RefundController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {

        Charge charge = payments.getCharge(request.charge());
        Amount amount = request.amount() == null
                ? null
                : Amount.of(request.amount(), charge.amount().currency());

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/refunds",
                request,
                RefundResponse.class,
                () -> {
                    Refund refund = payments.refund(request.charge(), amount, request.reason());
                    return IdempotencyService.Outcome.created(
                            RefundResponse.from(refund), refund.refundId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping
    public StripeList<RefundResponse> list(@RequestParam String charge) {
        payments.getCharge(charge);     // 404 if it doesn't exist
        return StripeList.of(
                payments.refundsFor(charge).stream().map(RefundResponse::from).toList(),
                false,
                "/v1/refunds");
    }
}
