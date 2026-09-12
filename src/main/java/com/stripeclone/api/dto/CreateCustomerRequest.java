package com.stripeclone.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

public record CreateCustomerRequest(
        @Email(message = "email must be a valid address")
        String email,

        String name,

        @Pattern(regexp = "(?i)[a-z]{3}", message = "currency must be a 3-letter code")
        String currency
) {}
