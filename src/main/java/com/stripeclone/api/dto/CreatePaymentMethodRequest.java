package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentMethodRequest(
        @NotBlank(message = "type is required")
        String type,

        // @Valid is what makes the constraints inside Card actually run. Without it the
        // nested record is skipped entirely and a bad expiry month falls through to the
        // database CHECK, which surfaces as a 500 rather than a 400.
        @Valid
        @NotNull(message = "card is required")
        Card card
) {
    public record Card(
            @NotBlank(message = "card.number is required")
            String number,

            @JsonProperty("exp_month")
            @NotNull(message = "card.exp_month is required")
            @Min(value = 1, message = "card.exp_month must be between 1 and 12")
            @Max(value = 12, message = "card.exp_month must be between 1 and 12")
            Integer expMonth,

            @JsonProperty("exp_year")
            @NotNull(message = "card.exp_year is required")
            @Min(value = 2024, message = "card.exp_year is in the past")
            Integer expYear,

            String cvc
    ) {}
}
