package com.stripeclone.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stripeclone.webhook.WebhookDelivery;
import com.stripeclone.webhook.WebhookEndpoint;
import com.stripeclone.webhook.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/v1/webhook_endpoints")
public class WebhookEndpointController {

    private final WebhookService webhooks;

    public WebhookEndpointController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    public ResponseEntity<EndpointResponse> create(@Valid @RequestBody CreateRequest request) {
        WebhookEndpoint endpoint = webhooks.register(
                request.url(), Set.copyOf(request.enabledEvents()), request.description());

        // The secret is returned once, on creation, and never again. Same as Stripe:
        // if you lose it you roll the endpoint, you don't look it up.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EndpointResponse.withSecret(endpoint));
    }

    @GetMapping("/{id}")
    public EndpointResponse retrieve(@PathVariable String id) {
        return EndpointResponse.from(webhooks.getEndpoint(id));
    }

    @GetMapping
    public StripeList<EndpointResponse> list(
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return StripeList.of(
                webhooks.listEndpoints(limit).stream().map(EndpointResponse::from).toList(),
                false,
                "/v1/webhook_endpoints");
    }

    @PostMapping("/{id}/disable")
    public EndpointResponse disable(@PathVariable String id) {
        return EndpointResponse.from(
                webhooks.setStatus(id, WebhookEndpoint.Status.DISABLED));
    }

    @PostMapping("/{id}/enable")
    public EndpointResponse enable(@PathVariable String id) {
        return EndpointResponse.from(
                webhooks.setStatus(id, WebhookEndpoint.Status.ENABLED));
    }

    @DeleteMapping("/{id}")
    public DeletedResponse delete(@PathVariable String id) {
        webhooks.delete(id);
        return new DeletedResponse(id, "webhook_endpoint", true);
    }

    /** What was sent to this endpoint, newest first. Useful when debugging a receiver. */
    @GetMapping("/{id}/deliveries")
    public StripeList<DeliveryResponse> deliveries(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "20") int limit) {

        webhooks.getEndpoint(id);
        return StripeList.of(
                webhooks.deliveriesForEndpoint(id, limit).stream()
                        .map(DeliveryResponse::from)
                        .toList(),
                false,
                "/v1/webhook_endpoints/" + id + "/deliveries");
    }

    public record CreateRequest(
            @NotBlank(message = "url is required")
            @Pattern(regexp = "^https?://.+", message = "url must be http or https")
            String url,

            @NotEmpty(message = "enabled_events must not be empty")
            @JsonProperty("enabled_events")
            List<String> enabledEvents,

            String description
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EndpointResponse(
            String id,
            String object,
            String url,
            String secret,
            String description,
            @JsonProperty("enabled_events") List<String> enabledEvents,
            String status,
            long created
    ) {

        static EndpointResponse from(WebhookEndpoint endpoint) {
            return build(endpoint, null);
        }

        static EndpointResponse withSecret(WebhookEndpoint endpoint) {
            return build(endpoint, endpoint.secret());
        }

        private static EndpointResponse build(WebhookEndpoint endpoint, String secret) {
            return new EndpointResponse(
                    endpoint.endpointId(),
                    "webhook_endpoint",
                    endpoint.url(),
                    secret,
                    endpoint.description(),
                    endpoint.sortedEvents(),
                    endpoint.status().wireValue(),
                    endpoint.createdAt() == null ? 0 : endpoint.createdAt().getEpochSecond());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryResponse(
            String id,
            String object,
            @JsonProperty("endpoint") String endpointId,
            @JsonProperty("event") String eventId,
            @JsonProperty("event_type") String eventType,
            String status,
            int attempts,
            @JsonProperty("response_status") Integer responseStatus,
            @JsonProperty("last_error") String lastError,
            long created
    ) {

        static DeliveryResponse from(WebhookDelivery delivery) {
            return new DeliveryResponse(
                    delivery.deliveryId(),
                    "webhook_delivery",
                    delivery.endpointId(),
                    delivery.eventId(),
                    delivery.eventType(),
                    delivery.status().name().toLowerCase(),
                    delivery.attempts(),
                    delivery.responseStatus(),
                    delivery.lastError(),
                    delivery.createdAt().getEpochSecond());
        }
    }

    public record DeletedResponse(String id, String object, boolean deleted) {}
}
