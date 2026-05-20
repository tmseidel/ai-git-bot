package org.remus.giteabot.bitbucket;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the M4 / wave 2 iteration 4 native-upload override
 * {@link BitbucketApiClient#attachPullRequestArtifact(String, String, Long, String, String, byte[])}.
 */
class BitbucketApiClientArtifactUploadTest {

    private static final RepositoryCredentials CREDS = RepositoryCredentials.of(
            "https://api.bitbucket.org/2.0", "https://bitbucket.org", "bb_token");

    @Test
    void inlineableTextArtifact_doesNotCallDownloadsEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/pullrequests/3/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content.raw").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**out.log**"),
                        org.hamcrest.Matchers.containsString("ok"))))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("ws", "repo", 3L,
                "out.log", "text/plain",
                "ok\n".getBytes(StandardCharsets.UTF_8));

        server.verify();
    }

    @Test
    void largeBinaryArtifact_uploadsAndLinks() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/downloads"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED));

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/pullrequests/3/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content.raw").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**trace.zip**"),
                        org.hamcrest.Matchers.containsString("https://bitbucket.org/ws/repo/downloads/trace.zip"))))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("ws", "repo", 3L,
                "trace.zip", "application/zip", new byte[64 * 1024]);

        server.verify();
    }

    @Test
    void uploadFailure_fallsBackToSummaryComment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/downloads"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/pullrequests/3/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content.raw").value(org.hamcrest.Matchers.containsString("SHA-256:")))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("ws", "repo", 3L,
                "trace.zip", "application/zip", new byte[64 * 1024]);

        server.verify();
    }

    @Test
    void downloadUrl_encodesSpacesAndSpecialChars() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = new BitbucketApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/downloads"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED));
        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/ws/repo/pullrequests/4/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content.raw").value(
                        org.hamcrest.Matchers.containsString(
                                "https://bitbucket.org/ws/repo/downloads/trace%20log.zip")))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact("ws", "repo", 4L,
                "trace log.zip", "application/zip", new byte[64 * 1024]);

        server.verify();
    }
}

