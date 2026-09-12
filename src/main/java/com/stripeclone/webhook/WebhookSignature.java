package com.stripeclone.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Stripe's webhook signature scheme.
 *
 * <p>The header looks like {@code t=1614556800,v1=5257a869e7...}: a timestamp and an
 * HMAC-SHA256 over {@code "<timestamp>.<body>"} keyed with the endpoint's secret.
 *
 * <p>Two details are what make it worth copying rather than inventing something.
 *
 * <p>The timestamp is inside the signed string, not just alongside it. If it were only a
 * header field an attacker could replay yesterday's request with today's timestamp and the
 * signature would still check out. Signing it means changing it invalidates the signature,
 * so the age check below can actually be trusted.
 *
 * <p>Comparison is constant-time. A normal string equals returns as soon as it finds a
 * difference, and the time it took leaks how many leading characters were right. Given
 * enough attempts that's sufficient to reconstruct a valid signature one character at a
 * time. {@link MessageDigest#isEqual} always looks at every byte.
 */
public final class WebhookSignature {

    /** How old a signed request may be before it's rejected as a possible replay. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /** Builds the {@code Stripe-Signature} header value. */
    public static String sign(String payload, String secret, Instant timestamp) {
        long seconds = timestamp.getEpochSecond();
        String signature = computeHmac(seconds + "." + payload, secret);
        return "t=" + seconds + ",v1=" + signature;
    }

    public static String sign(String payload, String secret) {
        return sign(payload, secret, Instant.now());
    }

    /**
     * Checks a header against a body.
     *
     * @return true only if the signature matches and the timestamp is recent enough
     */
    public static boolean verify(
            String payload, String header, String secret, Duration tolerance, Instant now) {

        Parsed parsed = parse(header);
        if (parsed == null) {
            return false;
        }

        Instant signedAt = Instant.ofEpochSecond(parsed.timestamp);
        Duration age = Duration.between(signedAt, now).abs();
        if (age.compareTo(tolerance) > 0) {
            return false;
        }

        String expected = computeHmac(parsed.timestamp + "." + payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parsed.signature.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(String payload, String header, String secret) {
        return verify(payload, header, secret, DEFAULT_TOLERANCE, Instant.now());
    }

    /** Generates an endpoint secret, in Stripe's {@code whsec_} style. */
    public static String generateSecret() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private static String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid webhook secret", e);
        }
    }

    private static Parsed parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        Long timestamp = null;
        String signature = null;

        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            switch (pair[0]) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(pair[1]);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                // Only v1 is understood. A future v2 would be added here rather than
                // replacing this, so old receivers keep working.
                case "v1" -> signature = pair[1];
                default -> { }
            }
        }

        if (timestamp == null || signature == null) {
            return null;
        }
        return new Parsed(timestamp, signature);
    }

    private record Parsed(long timestamp, String signature) {
    }
}
