package com.stripeclone.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AttachRequest(
        @NotBlank(message = "customer is required")
        String customer
) {}
