package com.stripeclone.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateRefundRequest(
        @NotBlank(message = "charge is required")
        String charge,

        @Min(value = 1, message = "amount must be at least 1")
        Long amount,

        String reason
) {}
