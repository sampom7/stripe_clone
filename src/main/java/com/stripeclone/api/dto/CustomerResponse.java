package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stripeclone.payment.Customer;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerResponse(
        String id,
        String object,
        String email,
        String name,
        long created
) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.customerId(),
                "customer",
                customer.email(),
                customer.name(),
                customer.createdAt() == null ? 0 : customer.createdAt().getEpochSecond());
    }
}
