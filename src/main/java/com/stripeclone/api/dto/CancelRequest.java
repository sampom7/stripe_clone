package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CancelRequest(
        @JsonProperty("cancellation_reason")
        String cancellationReason
) {}
