package com.stripeclone.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Posts webhooks over HTTP.
 *
 * <p>Short timeouts on purpose. A receiver that takes thirty seconds to answer is a
 * receiver doing work it should have queued, and waiting on it holds a database row lock
 * in the poller. Stripe's own guidance is to acknowledge quickly and process afterwards.
 */
@Component
public class HttpWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookSender.class);

    private final HttpClient client;

    @Value("${webhook.request-timeout-seconds:10}")
    private long requestTimeoutSeconds;

    public HttpWebhookSender() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Result send(String url, String payload, String signatureHeader, String eventId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Stripe-Signature", signatureHeader)
                    .header("User-Agent", "StripeClone/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Result.ok(response.statusCode());
            }
            return Result.failed(response.statusCode(),
                    "Endpoint returned " + response.statusCode());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(null, "Interrupted while sending");
        } catch (Exception e) {
            log.debug("Webhook to {} failed: {}", url, e.toString());
            return Result.failed(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
