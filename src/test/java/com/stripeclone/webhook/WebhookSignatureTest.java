package com.stripeclone.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final String PAYLOAD = "{\"id\":\"evt_123\",\"type\":\"charge.succeeded\"}";

    @Test
    void a_signature_verifies_against_the_body_it_was_made_from() {
        String header = WebhookSignature.sign(PAYLOAD, SECRET);

        assertThat(WebhookSignature.verify(PAYLOAD, header, SECRET)).isTrue();
    }

    @Test
    void the_header_carries_a_timestamp_and_a_v1_signature() {
        String header = WebhookSignature.sign(PAYLOAD, SECRET);

        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
    }

    @Test
    @DisplayName("changing one byte of the body breaks the signature")
    void a_tampered_body_is_rejected() {
        String header = WebhookSignature.sign(PAYLOAD, SECRET);
        String tampered = PAYLOAD.replace("evt_123", "evt_124");

        assertThat(WebhookSignature.verify(tampered, header, SECRET)).isFalse();
    }

    @Test
    void the_wrong_secret_is_rejected() {
        String header = WebhookSignature.sign(PAYLOAD, SECRET);

        assertThat(WebhookSignature.verify(PAYLOAD, header, "whsec_a_different_secret"))
                .isFalse();
    }

    @Test
    @DisplayName("an old signature is rejected even though the hash is still correct")
    void a_stale_signature_is_rejected() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        String header = WebhookSignature.sign(PAYLOAD, SECRET, tenMinutesAgo);

        // The hash is fine; it's the age that fails.
        assertThat(WebhookSignature.verify(
                PAYLOAD, header, SECRET, Duration.ofMinutes(5), Instant.now())).isFalse();

        // With a wider tolerance the same header passes, which shows the hash was never
        // the problem.
        assertThat(WebhookSignature.verify(
                PAYLOAD, header, SECRET, Duration.ofMinutes(30), Instant.now())).isTrue();
    }

    @Test
    @DisplayName("moving the timestamp forward does not rescue an old signature")
    void the_timestamp_is_covered_by_the_signature() {
        // This is the reason the timestamp is signed rather than merely sent alongside.
        // An attacker replaying an old body with a fresh timestamp gets nowhere.
        Instant old = Instant.now().minus(Duration.ofHours(2));
        String original = WebhookSignature.sign(PAYLOAD, SECRET, old);
        String justTheSignature = original.substring(original.indexOf("v1="));

        String forged = "t=" + Instant.now().getEpochSecond() + "," + justTheSignature;

        assertThat(WebhookSignature.verify(PAYLOAD, forged, SECRET)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("malformed headers are rejected rather than throwing")
    @ValueSource(strings = {
            "",
            "garbage",
            "t=notanumber,v1=abc",
            "v1=abcdef",
            "t=1614556800",
            "t=1614556800,v2=abcdef"
    })
    void malformed_headers_are_rejected(String header) {
        assertThat(WebhookSignature.verify(PAYLOAD, header, SECRET)).isFalse();
    }

    @Test
    void a_null_header_is_rejected() {
        assertThat(WebhookSignature.verify(PAYLOAD, null, SECRET)).isFalse();
    }

    @Test
    void generated_secrets_are_unique_and_prefixed() {
        String first = WebhookSignature.generateSecret();
        String second = WebhookSignature.generateSecret();

        assertThat(first).startsWith("whsec_").isNotEqualTo(second);
        assertThat(first).hasSize("whsec_".length() + 48);
    }

    @Test
    void the_same_input_always_signs_the_same_way() {
        Instant fixed = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(WebhookSignature.sign(PAYLOAD, SECRET, fixed))
                .isEqualTo(WebhookSignature.sign(PAYLOAD, SECRET, fixed));
    }

    @Test
    void a_signature_from_the_near_future_is_still_accepted() {
        // Clocks drift. Rejecting anything slightly ahead would break receivers whose
        // clock runs a few seconds fast.
        Instant slightlyAhead = Instant.now().plusSeconds(30);
        String header = WebhookSignature.sign(PAYLOAD, SECRET, slightlyAhead);

        assertThat(WebhookSignature.verify(PAYLOAD, header, SECRET)).isTrue();
    }
}
