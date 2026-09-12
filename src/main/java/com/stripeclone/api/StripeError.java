package com.stripeclone.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stripe's error envelope.
 *
 * <p>Everything they return on a failure is wrapped in a single "error" object with a
 * type, a machine-readable code and a human message. Clients switch on type and code, so
 * the shape matters as much as the status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StripeError(ErrorBody error) {

    public static StripeError of(String type, String code, String message, String param) {
        return new StripeError(new ErrorBody(type, code, message, param, null));
    }

    public static StripeError invalidRequest(String message, String param) {
        return of("invalid_request_error", null, message, param);
    }

    public static StripeError invalidRequest(String code, String message, String param) {
        return of("invalid_request_error", code, message, param);
    }

    public static StripeError cardError(String code, String message, String declineCode) {
        return new StripeError(new ErrorBody(
                "card_error", code, message, "payment_method", declineCode));
    }

    public static StripeError apiError(String message) {
        return of("api_error", null, message, null);
    }

    public static StripeError idempotencyError(String message) {
        return of("idempotency_error", null, message, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            String type,
            String code,
            String message,
            String param,
            @JsonProperty("decline_code") String declineCode
    ) {}
}
