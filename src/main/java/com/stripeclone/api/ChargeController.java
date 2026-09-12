package com.stripeclone.api;

import com.stripeclone.api.dto.ChargeResponse;
import com.stripeclone.payment.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/charges")
public class ChargeController {

    private final PaymentService payments;

    public ChargeController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/{id}")
    public ChargeResponse retrieve(@PathVariable String id) {
        return ChargeResponse.from(payments.getCharge(id));
    }
}
