package com.stripeclone.api;

import com.stripeclone.api.dto.CreateCustomerRequest;
import com.stripeclone.api.dto.CustomerResponse;
import com.stripeclone.idempotency.IdempotencyService;
import com.stripeclone.money.Currency;
import com.stripeclone.payment.Customer;
import com.stripeclone.payment.CustomerNotFoundException;
import com.stripeclone.payment.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public CustomerController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCustomerRequest request) {

        Currency currency = request.currency() == null
                ? Currency.USD
                : Currency.of(request.currency());

        var result = idempotency.execute(
                idempotencyKey,
                "POST /v1/customers",
                request,
                CustomerResponse.class,
                () -> {
                    Customer customer = payments.createCustomer(
                            request.email(), request.name(), currency);
                    return IdempotencyService.Outcome.created(
                            CustomerResponse.from(customer), customer.customerId());
                });

        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping("/{id}")
    public CustomerResponse retrieve(@PathVariable String id) {
        Customer customer = payments.findCustomer(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        return CustomerResponse.from(customer);
    }
}
