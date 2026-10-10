package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.WorkflowDispatchRequest;
import org.remus.giteabot.repository.WorkflowRunStatus;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link BitbucketApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class BitbucketApiClientTest {

    private static RepositoryCredentials credsWithUsername() {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "myuser", "bb_token");
    }

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "bb_token");
    }

    @Test
    void implementsRepositoryApiClient() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://api.bitbucket.org/2.0", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://bitbucket.org", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("bb_token", client.getToken());
    }

    @Test
    void addReaction_noOp() {
        // Bitbucket doesn't support reactions; verify no exception is thrown
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertDoesNotThrow(() -> client.addReaction("workspace", "repo", 1L, "+1"));
    }

    @Test
    void getCredentials_returnsUsername() {
        BitbucketApiClient client = new BitbucketApiClient(null, credsWithUsername());
        assertEquals("myuser", client.getCredentials().username());
        assertTrue(client.getCredentials().hasUsername());
    }

    @Test
    void getAuthenticatedAccount_fetchesUserOnceAndMemoizesIt() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo("https://api.bitbucket.org/2.0/user"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"type":"user","uuid":"{bot-uuid}","account_id":"557058:bot","nickname":"ai-bot",
                         "display_name":"AI Bot","links":{"avatar":{"href":"https://example.invalid/a.png"}}}
                        """, MediaType.APPLICATION_JSON));

        BitbucketAccount account = client.getAuthenticatedAccount();

        assertEquals(new BitbucketAccount("{bot-uuid}", "557058:bot", "ai-bot", "AI Bot"), account);
        assertSame(account, client.getAuthenticatedAccount());
        assertEquals("@{557058:bot}", account.mention());
        assertEquals(java.util.Optional.of("AI Bot"), client.getBotMentionName());
        server.verify();
    }

    @Test
    void getAuthenticatedAccount_rejectsBlankIds() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo("https://api.bitbucket.org/2.0/user"))
                .andRespond(withSuccess("""
                        {"uuid":" ","account_id":"","display_name":"AI Bot"}
                        """, MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, client::getAuthenticatedAccount);
        server.verify();
    }

    @Test
    void dispatchWorkflow_postsToPipelinesBelowApiBaseUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/workspace/repo/pipelines/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.target.ref_name").value("feature"))
                .andExpect(jsonPath("$.target.selector.pattern").value("preview"))
                .andRespond(withSuccess("{\"uuid\":\"{run-1}\"}", MediaType.APPLICATION_JSON));

        String runId = client.dispatchWorkflow(
                new WorkflowDispatchRequest("workspace", "repo", "preview", "feature", Map.of()));

        server.verify();
        assertEquals("{run-1}", runId);
    }

    @Test
    void getWorkflowRun_readsPipelineBelowApiBaseUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/workspace/repo/pipelines/%7Brun-1%7D"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"state\":{\"name\":\"COMPLETED\",\"result\":{\"name\":\"SUCCESSFUL\"}}}",
                        MediaType.APPLICATION_JSON));

        WorkflowRunStatus status = client.getWorkflowRun("workspace", "repo", "{run-1}");

        server.verify();
        assertEquals(WorkflowRunStatus.COMPLETED_SUCCESS, status);
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithPageLength() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), creds());

        server.expect(requestTo(
                        "https://api.bitbucket.org/2.0/repositories/workspace/repo/issues/42/comments?pagelen=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"values\":[{\"id\":301,\"content\":{\"raw\":\"First comment\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("workspace", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(301, ((Number) comments.getFirst().get("id")).intValue());
        assertInstanceOf(Map.class, comments.getFirst().get("content"));
    }
}
