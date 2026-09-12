package com.stripeclone.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record WebhookEndpoint(
        String endpointId,
        String url,
        String secret,
        String description,
        Set<String> enabledEvents,
        Status status,
        Instant createdAt
) {

    public enum Status {
        ENABLED,
        DISABLED;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    /** Whether this endpoint wants a given event. "*" subscribes to everything. */
    public boolean wants(String eventType) {
        return status == Status.ENABLED
                && (enabledEvents.contains("*") || enabledEvents.contains(eventType));
    }

    public List<String> sortedEvents() {
        return enabledEvents.stream().sorted().toList();
    }
}
