package com.stripeclone.webhook;

import com.stripeclone.common.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository repository;

    public WebhookService(WebhookRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WebhookEndpoint register(String url, Set<String> events, String description) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("enabled_events must not be empty");
        }

        WebhookEndpoint endpoint = new WebhookEndpoint(
                Ids.generate("we"),
                url,
                WebhookSignature.generateSecret(),
                description,
                events,
                WebhookEndpoint.Status.ENABLED,
                null);

        repository.insertEndpoint(endpoint);
        log.debug("Registered webhook endpoint {} for {}", endpoint.endpointId(), url);
        return repository.findEndpoint(endpoint.endpointId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint getEndpoint(String endpointId) {
        return repository.findEndpoint(endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> listEndpoints(int limit) {
        return repository.listEndpoints(limit);
    }

    @Transactional
    public WebhookEndpoint setStatus(String endpointId, WebhookEndpoint.Status status) {
        getEndpoint(endpointId);
        repository.setEndpointStatus(endpointId, status);
        return repository.findEndpoint(endpointId).orElseThrow();
    }

    @Transactional
    public void delete(String endpointId) {
        getEndpoint(endpointId);
        repository.deleteEndpoint(endpointId);
    }

    /**
     * Queues a copy of an event for every endpoint that wants it.
     *
     * @return how many deliveries were queued, not counting ones that already existed
     */
    @Transactional
    public int fanOut(String eventId, String eventType, String payload) {
        List<WebhookEndpoint> endpoints = repository.findEnabledEndpoints();

        int queued = 0;
        for (WebhookEndpoint endpoint : endpoints) {
            if (!endpoint.wants(eventType)) {
                continue;
            }
            boolean isNew = repository.queueDelivery(
                    Ids.generate("whd"), endpoint.endpointId(), eventId, eventType, payload);
            if (isNew) {
                queued++;
            }
        }

        if (queued > 0) {
            log.debug("Queued {} deliveries for event {}", queued, eventId);
        }
        return queued;
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveriesForEvent(String eventId) {
        return repository.findDeliveriesForEvent(eventId);
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveriesForEndpoint(String endpointId, int limit) {
        return repository.findDeliveriesForEndpoint(endpointId, limit);
    }

    @Transactional(readOnly = true)
    public Optional<WebhookDelivery> findDelivery(String deliveryId) {
        return repository.findDelivery(deliveryId);
    }

    /** Puts a failed delivery back in the queue. */
    @Transactional
    public void replay(String deliveryId) {
        repository.findDelivery(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such delivery: " + deliveryId));
        repository.requeue(deliveryId);
    }
}
