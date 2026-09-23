package org.remus.giteabot.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live end-to-end test for managed Gitea SSH keys: integration setup, host-key
 * preview, key registration, a real SSH clone through the bot workspace, and
 * managed-key cleanup — all against a real Gitea instance.
 *
 * <p>Runs only when {@code GITEA_E2E_URL} and {@code GITEA_E2E_TOKEN} are set;
 * otherwise the test is skipped. CI provides a local Gitea via
 * {@code systemtest/docker-compose-local-gitea.yml} (see
 * {@code .github/workflows/e2e-gitea-ssh.yml}). Requires {@code git},
 * {@code ssh}, {@code ssh-keygen} and {@code ssh-keyscan} on {@code PATH}.</p>
 */
@EnabledIfEnvironmentVariable(named = "GITEA_E2E_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GITEA_E2E_TOKEN", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gitea-ssh-e2e;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
class GiteaSshEndToEndTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private GitIntegrationService gitIntegrationService;
    @Autowired private GiteaSshSetupService setupService;
    @Autowired private GiteaClientFactory giteaClientFactory;
    @Autowired private WorkspaceService workspaceService;

    @Test
    void managedSshSetupCloneAndCleanupEndToEnd() throws Exception {
        String baseUrl = env("GITEA_E2E_URL");
        String token = env("GITEA_E2E_TOKEN");
        String owner = api(baseUrl, token, "GET", "/api/v1/user", null).get("login").asText();
        String repo = "ssh-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        api(baseUrl, token, "POST", "/api/v1/user/repos",
                "{\"name\":\"" + repo + "\",\"auto_init\":true,\"default_branch\":\"main\",\"private\":true}");
        try {
            GitIntegration input = new GitIntegration();
            input.setName("e2e-" + repo);
            input.setProviderType(RepositoryType.GITEA);
            input.setUrl(baseUrl);
            input.setToken(token);
            GitIntegration saved = gitIntegrationService.save(input, false);
            assertNotNull(saved.getId());

            var preview = setupService.preview(saved.getId());
            assertFalse(preview.hostKeys().fingerprints().isEmpty(),
                    "expected scanned SSH host fingerprints");

            GitIntegration configured = setupService.setup(saved.getId(), saved.getLockVersion(),
                    preview.hostKeys().confirmation(), true);
            assertEquals(GitTransport.SSH, configured.getTransport());
            assertNotNull(configured.getSshRemoteKeyId());
            assertNotNull(configured.getSshRemoteKeyTitle());

            RepositoryApiClient client = giteaClientFactory.getApiClient(configured);
            assertInstanceOf(GiteaApiClient.class, client);
            GiteaApiClient gitea = (GiteaApiClient) client;
            assertTrue(gitea.getSshKeyIdsByTitle(configured.getSshRemoteKeyTitle())
                    .contains(configured.getSshRemoteKeyId()), "managed key visible via Gitea API");

            WorkspaceResult ws = workspaceService.prepareWorkspace(client, owner, repo, "main", null);
            assertTrue(ws.success(), "SSH clone via bot workspace: " + ws.error());
            try {
                assertTrue(Files.exists(ws.workspacePath().resolve("README.md")),
                        "expected auto_init README in SSH clone");
            } finally {
                workspaceService.cleanupWorkspace(ws.workspacePath());
            }

            GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(
                    configured.getId(), configured.getLockVersion());
            GitIntegration cleaned = setupService.removeManagedKey(pending, null);
            assertNotNull(cleaned, "managed key cleanup succeeds");
            assertFalse(cleaned.hasManagedSshKeyTracking());
            assertFalse(gitea.getSshKeyIdsByTitle(configured.getSshRemoteKeyTitle())
                    .contains(configured.getSshRemoteKeyId()), "managed key removed from Gitea");
        } finally {
            api(baseUrl, token, "DELETE", "/api/v1/repos/" + owner + "/" + repo, null);
        }
    }

    private String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be set");
        return value;
    }

    private JsonNode api(String baseUrl, String token, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "token " + token)
                .header("Content-Type", "application/json");
        switch (method) {
            case "POST" -> request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> request.DELETE();
            default -> request.GET();
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300,
                method + " " + path + " -> " + response.statusCode() + ": " + response.body());
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
}
