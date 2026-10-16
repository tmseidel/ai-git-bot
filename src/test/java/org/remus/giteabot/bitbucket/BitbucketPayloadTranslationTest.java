package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Bitbucket Cloud → WebhookPayload translation logic in
 * {@link BitbucketWebhookHandler#translatePayload(String, Map)}.
 */
class BitbucketPayloadTranslationTest {

    private final BitbucketWebhookHandler handler = new BitbucketWebhookHandler(null, null, null);

    @Test
    void translatePullRequestCreated_mapsAllFields() {
        Map<String, Object> raw = createPullRequestPayload();

        WebhookPayload payload = handler.translatePayload("pullrequest:created", raw);

        assertNotNull(payload);
        assertEquals("opened", payload.getAction());
        assertEquals("developer", payload.getSender().getLogin());
        assertEquals("myrepo", payload.getRepository().getName());
        assertEquals("workspace/myrepo", payload.getRepository().getFullName());
        assertEquals("workspace", payload.getRepository().getOwner().getLogin());
        assertNotNull(payload.getPullRequest());
        assertEquals(42L, payload.getPullRequest().getNumber());
        assertEquals("Add feature", payload.getPullRequest().getTitle());
        assertEquals("feature-branch", payload.getPullRequest().getHead().getRef());
        assertEquals("main", payload.getPullRequest().getBase().getRef());
    }

    @Test
    void translatePullRequestUpdated_mapsToSynchronized() {
        Map<String, Object> raw = createPullRequestPayload();

        WebhookPayload payload = handler.translatePayload("pullrequest:updated", raw);

        assertNotNull(payload);
        assertEquals("synchronized", payload.getAction());
    }

    @Test
    void translatePullRequestFulfilled_mapsToClosed() {
        Map<String, Object> raw = createPullRequestPayload();
        // Set state to MERGED for fulfilled
        Map<String, Object> pr = new HashMap<>((Map<String, Object>) raw.get("pullrequest"));
        pr.put("state", "MERGED");
        Map<String, Object> rawCopy = new HashMap<>(raw);
        rawCopy.put("pullrequest", pr);

        WebhookPayload payload = handler.translatePayload("pullrequest:fulfilled", rawCopy);

        assertNotNull(payload);
        assertEquals("closed", payload.getAction());
        assertTrue(payload.getPullRequest().getMerged());
    }

    @Test
    void translatePullRequestRejected_mapsToClosed() {
        Map<String, Object> raw = createPullRequestPayload();

        WebhookPayload payload = handler.translatePayload("pullrequest:rejected", raw);

        assertNotNull(payload);
        assertEquals("closed", payload.getAction());
    }

    @Test
    void translatePullRequestCommentCreated_mapsComment() {
        Map<String, Object> raw = createCommentPayload("@ai_bot review this", null);

        WebhookPayload payload = handler.translatePayload("pullrequest:comment_created", raw);

        assertNotNull(payload);
        assertEquals("created", payload.getAction());
        assertNotNull(payload.getComment());
        assertEquals(55L, payload.getComment().getId());
        assertEquals("@ai_bot review this", payload.getComment().getBody());
        assertEquals("commenter", payload.getComment().getUser().getLogin());
        assertNotNull(payload.getIssue());
        assertNotNull(payload.getIssue().getPullRequest());
    }

    @Test
    void translatePullRequestCommentCreated_inlineComment_mapsPathAndLine() {
        Map<String, Object> inline = Map.of(
                "path", "src/Main.java",
                "to", 15
        );
        Map<String, Object> raw = createCommentPayload("@ai_bot explain this", inline);

        WebhookPayload payload = handler.translatePayload("pullrequest:comment_created", raw);

        assertNotNull(payload);
        assertNotNull(payload.getComment());
        assertEquals("src/Main.java", payload.getComment().getPath());
        assertEquals(15, payload.getComment().getLine());
    }

    @Test
    void translateUnknownEvent_returnsNull() {
        assertNull(handler.translatePayload("repo:push", Map.of()));
    }

    @Test
    void translatePullRequestCreated_extractsActorNickname() {
        Map<String, Object> raw = createPullRequestPayload();

        WebhookPayload payload = handler.translatePayload("pullrequest:created", raw);

        assertNotNull(payload);
        assertEquals("developer", payload.getSender().getLogin());
    }

    @Test
    void translateRealBitbucketPayload_mapsAllFields() {
        // Real Bitbucket Cloud payload from a pullrequest:created event
        String json = """
            {
              "repository": {
                "type": "repository",
                "full_name": "my-cool-java-project/test-project",
                "name": "test-project",
                "uuid": "{b1233004-7268-4fb3-bc7e-0eec6de885ce}",
                "owner": {
                  "display_name": "my-cool-java-project",
                  "type": "team",
                  "uuid": "{9bbec7f6-1eee-4df9-a439-398066182a71}",
                  "username": "my-cool-java-project"
                }
              },
              "actor": {
                "display_name": "Tom Seidel",
                "type": "user",
                "uuid": "{3c780092-38a5-4801-adb7-0789d2697f64}",
                "account_id": "5aec2ce958742e214dd095ef",
                "nickname": "tmseidel"
              },
              "pullrequest": {
                "id": 16,
                "title": "README.md edited online with Bitbucket",
                "description": "",
                "state": "OPEN",
                "source": {
                  "branch": { "name": "Test-branch" },
                  "commit": { "hash": "4401032e1b70" }
                },
                "destination": {
                  "branch": { "name": "main" },
                  "commit": { "hash": "1c7868ba0d14" }
                }
              }
            }
            """;

        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.readValue(json, Map.class);

        WebhookPayload payload = handler.translatePayload("pullrequest:created", raw);

        assertNotNull(payload);
        assertEquals("opened", payload.getAction());

        // Actor/sender
        assertNotNull(payload.getSender());
        assertEquals("tmseidel", payload.getSender().getLogin());

        // Repository
        assertNotNull(payload.getRepository());
        assertEquals("test-project", payload.getRepository().getName());
        assertEquals("my-cool-java-project/test-project", payload.getRepository().getFullName());
        assertNotNull(payload.getRepository().getOwner());
        assertEquals("my-cool-java-project", payload.getRepository().getOwner().getLogin());

        // Pull request
        assertNotNull(payload.getPullRequest());
        assertEquals(16L, payload.getPullRequest().getNumber());
        assertEquals("README.md edited online with Bitbucket", payload.getPullRequest().getTitle());
        assertEquals("Test-branch", payload.getPullRequest().getHead().getRef());
        assertEquals("main", payload.getPullRequest().getBase().getRef());
        assertEquals("4401032e1b70", payload.getPullRequest().getHead().getSha());
        assertEquals("1c7868ba0d14", payload.getPullRequest().getBase().getSha());
    }

    // ---- Helper methods ----

    private Map<String, Object> createPullRequestPayload() {
        Map<String, Object> source = new HashMap<>();
        source.put("branch", Map.of("name", "feature-branch"));
        source.put("commit", Map.of("hash", "abc123"));

        Map<String, Object> destination = new HashMap<>();
        destination.put("branch", Map.of("name", "main"));
        destination.put("commit", Map.of("hash", "def456"));

        Map<String, Object> pullrequest = new HashMap<>();
        pullrequest.put("id", 42);
        pullrequest.put("title", "Add feature");
        pullrequest.put("description", "Feature description");
        pullrequest.put("state", "OPEN");
        pullrequest.put("source", source);
        pullrequest.put("destination", destination);

        Map<String, Object> raw = new HashMap<>();
        raw.put("pullrequest", pullrequest);
        raw.put("actor", Map.of("nickname", "developer", "display_name", "Developer User"));
        raw.put("repository", Map.of(
                "name", "myrepo",
                "full_name", "workspace/myrepo",
                "uuid", "{12345}",
                "owner", Map.of("nickname", "workspace", "display_name", "Workspace Owner")
        ));

        return raw;
    }

    @Test
    void teamOwnedRepository_usesWorkspaceSlugInsteadOfLegacyOwnerUsername() {
        Map<String, Object> raw = createPullRequestPayload();
        raw.put("repository", Map.of(
                "name", "ai-git-bot-test-bitbucket",
                "full_name", "postremus/ai-git-bot-test-bitbucket",
                "owner", Map.of("type", "team", "username", "postremus1", "display_name", "postremus"),
                "workspace", Map.of("type", "workspace", "slug", "postremus", "name", "postremus")));

        WebhookPayload payload = handler.translatePayload("pullrequest:created", raw);

        assertEquals("postremus", payload.getRepository().getOwner().getLogin());
    }

    @Test
    void repositoryWithoutWorkspace_fallsBackToFullNamePrefix() {
        Map<String, Object> raw = createPullRequestPayload();
        raw.put("repository", Map.of(
                "name", "repo",
                "full_name", "postremus/repo",
                "owner", Map.of("username", "postremus1")));

        WebhookPayload payload = handler.translatePayload("pullrequest:created", raw);

        assertEquals("postremus", payload.getRepository().getOwner().getLogin());
    }

    private Map<String, Object> createCommentPayload(String body, Map<String, Object> inline) {
        Map<String, Object> raw = createPullRequestPayload();
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 55);
        comment.put("content", Map.of("raw", body));
        comment.put("user", Map.of("nickname", "commenter", "display_name", "Commenter User"));
        if (inline != null) {
            comment.put("inline", inline);
        }
        raw.put("comment", comment);
        return raw;
    }
}
