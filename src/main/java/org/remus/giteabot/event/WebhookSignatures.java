package org.remus.giteabot.event;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Building blocks for {@link WebhookTranslator#verifySignature}: the HMAC that GitHub, Gitea
 * and Bitbucket compute over the request body, and a constant-time comparison for both those
 * signatures and the plain shared-secret tokens GitLab and Azure DevOps send.
 */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    /** The lowercase hex HMAC-SHA256 of {@code body} under {@code secret}. */
    public static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }

    /**
     * Compares the expected value with the one the request carried, in constant time and
     * ignoring surrounding whitespace. A missing value never matches.
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }
}
