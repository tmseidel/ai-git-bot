package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
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
