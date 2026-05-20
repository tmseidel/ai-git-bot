package org.remus.giteabot.gitea;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the M4 / wave 2 iteration 4 native-upload override
 * {@link GiteaApiClient#attachPullRequestArtifact(String, String, Long, String, String, byte[])}.
 */
class GiteaApiClientArtifactUploadTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitea.example.com", "https://gitea.example.com", "gitea-token");

    @Test
    void inlineableTextArtifact_doesNotCallAssetsEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/5/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**out.log**"),
                        org.hamcrest.Matchers.containsString("ok"),
                        org.hamcrest.Matchers.containsString("```"))))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("owner", "repo", 5L,
                "out.log", "text/plain",
                "ok\n".getBytes(StandardCharsets.UTF_8));

        server.verify();
    }

    @Test
    void largeBinaryArtifact_uploadsAndLinks() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/5/assets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess(
                        "{\"id\":1,\"name\":\"trace.zip\",\"size\":65536,"
                                + "\"uuid\":\"abc-123\","
                                + "\"browser_download_url\":\"https://gitea.example.com/attachments/abc-123\"}",
                        MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/5/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**trace.zip**"),
                        org.hamcrest.Matchers.containsString("https://gitea.example.com/attachments/abc-123"))))
                .andRespond(withSuccess());

        byte[] payload = new byte[64 * 1024];
        client.attachPullRequestArtifact("owner", "repo", 5L, "trace.zip", "application/zip", payload);

        server.verify();
    }

    @Test
    void uploadFailure_fallsBackToSummaryComment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GiteaApiClient client = new GiteaApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/5/assets"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo/issues/5/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("SHA-256:")))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("owner", "repo", 5L,
                "trace.zip", "application/zip", new byte[64 * 1024]);

        server.verify();
    }
}

