package com.stripeclone.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripeclone.outbox.OutboxEvent;
import com.stripeclone.outbox.OutboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The event log.
 *
 * <p>Every event the system has produced, whether or not a webhook ever reached anyone.
 * This is what a receiver falls back on after an outage: read what was missed instead of
 * waiting for retries that may already have given up.
 */
@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final OutboxService outbox;
    private final ObjectMapper json;

    public EventController(OutboxService outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @GetMapping("/{id}")
    public EventResponse retrieve(@PathVariable String id) {
        OutboxEvent event = outbox.find(id)
                .orElseThrow(() -> new EventNotFoundException(id));
        return toResponse(event);
    }

    @GetMapping
    public StripeList<EventResponse> list(
            @RequestParam(required = false, defaultValue = "10") int limit) {

        int capped = Math.min(Math.max(limit, 1), 100);
        return StripeList.of(
                outbox.listRecent(capped).stream().map(this::toResponse).toList(),
                false,
                "/v1/events");
    }

    private EventResponse toResponse(OutboxEvent event) {
        Map<String, Object> data;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(event.payload(), Map.class);
            data = Map.of("object", parsed);
        } catch (JsonProcessingException e) {
            data = Map.of("object", Map.of("raw", event.payload()));
        }

        return new EventResponse(
                event.eventId(),
                "event",
                event.eventType(),
                data,
                event.createdAt().getEpochSecond());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventResponse(
            String id,
            String object,
            String type,
            @JsonProperty("data") Map<String, Object> data,
            long created
    ) {}

    public static class EventNotFoundException extends RuntimeException {
        public EventNotFoundException(String eventId) {
            super("No such event: " + eventId);
        }
    }
}
