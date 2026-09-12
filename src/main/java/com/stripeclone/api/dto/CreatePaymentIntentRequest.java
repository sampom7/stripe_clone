package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreatePaymentIntentRequest(
        @NotNull(message = "amount is required")
        @Min(value = 1, message = "amount must be at least 1")
        Long amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "(?i)[a-z]{3}", message = "currency must be a 3-letter code")
        String currency,

        String customer,

        @JsonProperty("capture_method")
        String captureMethod,

        String description,

        /** Which merchant is being paid. Stripe infers this from the API key. */
        @NotBlank(message = "merchant_account is required")
        @JsonProperty("merchant_account")
        String merchantAccount
) {}
