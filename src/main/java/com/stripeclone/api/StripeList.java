package com.stripeclone.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Stripe's list envelope.
 *
 * <p>Always an object with {@code object: "list"}, the rows under {@code data}, and
 * {@code has_more} telling the client whether to page again. Never a bare JSON array,
 * which would leave nowhere to put the pagination flag later.
 */
public record StripeList<T>(
        String object,
        List<T> data,
        @JsonProperty("has_more") boolean hasMore,
        String url
) {
    public static <T> StripeList<T> of(List<T> data, boolean hasMore, String url) {
        return new StripeList<>("list", data, hasMore, url);
    }
}
