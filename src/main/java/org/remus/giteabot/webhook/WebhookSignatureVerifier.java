package org.remus.giteabot.webhook;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies the authenticity of inbound provider webhooks against an optional
 * per-bot signing secret (configured in the bot settings and at the git
 * provider).
 *
 * <ul>
 *   <li>GitHub: {@code X-Hub-Signature-256: sha256=<hex-hmac>}</li>
 *   <li>Gitea: {@code X-Gitea-Signature: <hex-hmac>}</li>
 *   <li>GitLab: {@code X-Gitlab-Token: <secret>} (plain equality)</li>
 *   <li>Bitbucket Cloud: {@code X-Hub-Signature: sha256=<hex-hmac>}</li>
 * </ul>
 *
 * All comparisons are constant-time. When a signing secret is configured for a
 * bot, requests without a valid signature are rejected - the URL path secret
 * then acts as routing/second factor only.
 */
@Slf4j
public final class WebhookSignatureVerifier {

    private WebhookSignatureVerifier() {
    }

    /**
     * Verifies the request. Returns {@code true} when no signing secret is
     * configured (backwards compatible) or the signature matches.
     *
     * @param providerType the bot's git provider
     * @param signingSecret the configured shared secret (may be {@code null}/blank)
     * @param headers request headers (keys are matched case-insensitively)
     * @param rawBody the exact request body bytes
     */
    public static boolean isValid(RepositoryType providerType, String signingSecret,
                                  Map<String, String> headers, byte[] rawBody) {
        if (signingSecret == null || signingSecret.isBlank()) {
            return true;
        }
        String expected;
        String actual;
        switch (providerType) {
            case GITHUB -> {
                expected = "sha256=" + hmacSha256Hex(signingSecret, rawBody);
                actual = header(headers, "X-Hub-Signature-256");
            }
            case GITEA -> {
                expected = hmacSha256Hex(signingSecret, rawBody);
                actual = header(headers, "X-Gitea-Signature");
            }
            case GITLAB -> {
                expected = signingSecret;
                actual = header(headers, "X-Gitlab-Token");
            }
            case BITBUCKET -> {
                expected = "sha256=" + hmacSha256Hex(signingSecret, rawBody);
                actual = header(headers, "X-Hub-Signature");
            }
            default -> {
                log.warn("Webhook signature verification not implemented for provider {}", providerType);
                return false;
            }
        }
        return constantTimeEquals(expected, actual);
    }

    private static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }
}
