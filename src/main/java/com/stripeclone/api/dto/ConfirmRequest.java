package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Confirm body.
 *
 * <p>The raw card number appears here only because this is a simulator with no tokenising
 * front end. A real integration would send a token created in the browser and the number
 * would never reach the server.
 */
public record ConfirmRequest(
        @JsonProperty("payment_method")
        String paymentMethod,

        @JsonProperty("card_number")
        String cardNumber
) {}
