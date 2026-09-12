package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/** Capture body. Omit the amount to take the whole authorized sum. */
public record CaptureRequest(
        @Min(value = 1, message = "amount_to_capture must be at least 1")
        @JsonProperty("amount_to_capture")
        Long amountToCapture
) {}
