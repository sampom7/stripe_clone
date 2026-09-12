package com.stripeclone.webhook;

public class WebhookEndpointNotFoundException extends RuntimeException {

    private final String endpointId;

    public WebhookEndpointNotFoundException(String endpointId) {
        super("No such webhook endpoint: " + endpointId);
        this.endpointId = endpointId;
    }

    public String endpointId() {
        return endpointId;
    }
}
