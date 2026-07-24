package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GiteaApiClient}.
 */
class GiteaApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "gitea-token");

    @Test
    void implementsRepositoryApiClient() {
        GiteaApiClient client = new GiteaApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getIssueComments_fetchesIssueCommentsWithLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo(
                        "https://gitea.example.com/api/v1/repos/owner/repo/issues/42/comments?limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":401,\"body\":\"First comment\"}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> comments = client.getIssueComments("owner", "repo", 42L);

        server.verify();
        assertEquals(1, comments.size());
        assertEquals(401, ((Number) comments.getFirst().get("id")).intValue());
        assertEquals("First comment", comments.getFirst().get("body"));
    }

    @Test
    void postReview_approve_submitsSingleReviewWithBodyAndEvent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.body").value("The findings"))
                .andExpect(jsonPath("$.event").value("APPROVED"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "The findings", PostReviewAction.APPROVE);

        server.verify();
    }

    @Test
    void postReview_none_submitsSingleCommentReview() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value("Just a comment"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andRespond(withSuccess());

        client.postReview("owner", "repo", 7L, "Just a comment", PostReviewAction.NONE);

        server.verify();
    }
}

