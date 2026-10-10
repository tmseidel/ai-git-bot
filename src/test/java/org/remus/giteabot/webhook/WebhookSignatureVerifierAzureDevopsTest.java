package org.remus.giteabot.webhook;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryType;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Azure DevOps Service Hooks send no HMAC over the body; authenticity is asserted with a
 * custom header carrying the signing secret, configured in the subscription's
 * "HTTP headers" field.
 */
class WebhookSignatureVerifierAzureDevopsTest {

    private static final byte[] BODY = "{\"eventType\":\"git.pullrequest.created\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void validWhenHeaderMatchesSecret() {
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, "s3cret",
                Map.of("X-AiGitBot-Token", "s3cret"), BODY));
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, "s3cret",
                Map.of("x-aigitbot-token", "s3cret"), BODY));
    }

    @Test
    void invalidWhenHeaderMismatches() {
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, "s3cret",
                Map.of("X-AiGitBot-Token", "wrong"), BODY));
    }

    @Test
    void invalidWhenHeaderAbsent() {
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, "s3cret", Map.of(), BODY));
    }

    @Test
    void validWhenNoSigningSecretConfigured() {
        // Shared contract across all providers: no secret means no verification.
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, null, Map.of(), BODY));
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.AZURE_DEVOPS, "  ", Map.of(), BODY));
    }
}
