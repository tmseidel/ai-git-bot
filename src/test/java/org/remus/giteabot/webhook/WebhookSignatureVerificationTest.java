package org.remus.giteabot.webhook;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.azuredevops.AzureDevopsWebhookTranslator;
import org.remus.giteabot.bitbucket.BitbucketWebhookTranslator;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.gitea.GiteaWebhookTranslator;
import org.remus.giteabot.github.GitHubWebhookTranslator;
import org.remus.giteabot.gitlab.GitLabWebhookTranslator;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signature scheme each provider's translator checks. Whether verification happens at
 * all (only with a signing secret configured) and the case-insensitive header lookup are
 * the controller's part, covered by {@link UnifiedWebhookControllerTest}.
 */
class WebhookSignatureVerificationTest {

    private static final String SECRET = "webhook-secret";
    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
    private static final String HMAC = WebhookSignatures.hmacSha256Hex(SECRET, BODY);

    private final WebhookTranslator gitea = new GiteaWebhookTranslator();
    private final WebhookTranslator github = new GitHubWebhookTranslator();
    private final WebhookTranslator gitlab = new GitLabWebhookTranslator();
    private final WebhookTranslator bitbucket = new BitbucketWebhookTranslator(null, null, null);
    private final WebhookTranslator azureDevops = new AzureDevopsWebhookTranslator(null, null);

    @Test
    void hmacSha256Hex_matchesAKnownValue() {
        // The widely published HMAC-SHA256 example: the helper must produce lowercase hex.
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                WebhookSignatures.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void github_checksTheBodyHmacInXHubSignature256() {
        assertTrue(github.verifySignature(SECRET, Map.of("X-Hub-Signature-256", "sha256=" + HMAC), BODY));
        assertFalse(github.verifySignature(SECRET, Map.of("X-Hub-Signature-256", "sha256=invalid"), BODY));
    }

    @Test
    void gitea_checksTheBodyHmacInXGiteaSignature() {
        assertTrue(gitea.verifySignature(SECRET, Map.of("X-Gitea-Signature", HMAC), BODY));
        assertFalse(gitea.verifySignature(SECRET, Map.of("X-Gitea-Signature", "invalid"), BODY));
    }

    @Test
    void bitbucket_checksTheBodyHmacInXHubSignature() {
        assertTrue(bitbucket.verifySignature(SECRET, Map.of("X-Hub-Signature", "sha256=" + HMAC), BODY));
        assertFalse(bitbucket.verifySignature(SECRET, Map.of("X-Hub-Signature", "sha256=invalid"), BODY));
    }

    @Test
    void gitlab_checksTheSharedSecretInXGitlabToken() {
        assertTrue(gitlab.verifySignature(SECRET, Map.of("X-Gitlab-Token", SECRET), BODY));
        assertFalse(gitlab.verifySignature(SECRET, Map.of("X-Gitlab-Token", "invalid"), BODY));
    }

    @Test
    void azureDevops_checksTheSharedSecretInXAiGitBotToken() {
        // Azure DevOps Service Hooks sign nothing; the subscription sends the secret as a header.
        assertTrue(azureDevops.verifySignature("s3cret", Map.of("X-AiGitBot-Token", "s3cret"), BODY));
        assertFalse(azureDevops.verifySignature("s3cret", Map.of("X-AiGitBot-Token", "wrong"), BODY));
    }

    @Test
    void missingHeader_isRejectedByEveryProvider() {
        for (WebhookTranslator translator : new WebhookTranslator[]{gitea, github, gitlab, bitbucket, azureDevops}) {
            assertFalse(translator.verifySignature(SECRET, Map.of(), BODY), translator.providerType().name());
        }
    }

    @Test
    void anotherProvidersHeader_isRejected() {
        // GitHub's and Bitbucket's headers differ only in the "-256" suffix.
        assertFalse(github.verifySignature(SECRET, Map.of("X-Hub-Signature", "sha256=" + HMAC), BODY));
        assertFalse(bitbucket.verifySignature(SECRET, Map.of("X-Hub-Signature-256", "sha256=" + HMAC), BODY));
    }
}
