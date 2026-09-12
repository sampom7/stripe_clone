package com.stripeclone.webhook;

/**
 * Sends one signed request to an endpoint.
 *
 * <p>An interface rather than a concrete HTTP client so tests can swap in something that
 * records calls and returns whatever status the test needs. Testing retry behaviour against
 * a real socket would mean standing up a server that fails on demand, which is a lot of
 * machinery for no extra confidence.
 */
public interface WebhookSender {

    Result send(String url, String payload, String signatureHeader, String eventId);

    /**
     * @param statusCode the HTTP status, or null if the request never got that far
     * @param error      what went wrong, or null on success
     */
    record Result(Integer statusCode, String error) {

        public static Result ok(int statusCode) {
            return new Result(statusCode, null);
        }

        public static Result failed(Integer statusCode, String error) {
            return new Result(statusCode, error);
        }

        /** 2xx means delivered. Everything else gets retried. */
        public boolean isSuccess() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }
}
