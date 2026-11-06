package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GiteaProgressCommentTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"id\":42}"})
    void existingCommentCallDoesNotRequireAResponseId(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "test-token"));
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/7/comments"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"body\":\"README Sync starting\"}"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        client.postPullRequestComment("owner", "repo", 7L, "README Sync starting");
        server.verify();
    }

    @Test
    void createsAndEditsOneIssueCommentForThePullRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "test-token"));
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/7/comments"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"body\":\"Starting review\"}"))
                .andRespond(withSuccess("{\"id\":2147483648,\"body\":\"Starting review\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/comments/2147483648"))
                .andExpect(method(HttpMethod.PATCH)).andExpect(content().json("{\"body\":\"Review completed\"}"))
                .andRespond(withSuccess());

        CommentId id = client.postPullRequestComment("owner", "repo", 7L, "Starting review");
        assertEquals(CommentId.NumericCommentId.of(2147483648L), id);
        client.updatePullRequestComment("owner", "repo", 7L, id, "Review completed");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"id\":0}", "{\"id\":-1}", "{\"id\":\"42\"}",
            "{\"id\":42.5}", "{\"id\":9223372036854775808}"})
    void returnsNoIdWhenTheResponseHasNoUsableId(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(),
                RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "test-token"));
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/7/comments"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertNull(client.postPullRequestComment("owner", "repo", 7L, "Starting review"));
        server.verify();
    }
}
