package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the M4 / wave 2 iteration 4 native-upload override
 * {@link GitLabApiClient#attachPullRequestArtifact(String, String, Long, String, String, byte[])}.
 *
 * <p>Three behaviours are exercised: a text artifact that the shared
 * {@code ArtifactCommentRenderer} can inline (no upload call), a large
 * binary artifact that triggers a real {@code /uploads} POST followed by
 * a comment with the returned Markdown, and an upload failure that
 * degrades gracefully to the summary comment.</p>
 */
class GitLabApiClientArtifactUploadTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitlab.example.com", "https://gitlab.example.com", "glpat-token");

    @Test
    void inlineableTextArtifact_doesNotCallUploadsEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/9/notes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**run.log**"),
                        org.hamcrest.Matchers.containsString("hello world"),
                        org.hamcrest.Matchers.containsString("```"))))
                .andRespond(withSuccess());

        client.attachPullRequestArtifact(
                "owner", "repo", 9L,
                "run.log", "text/plain",
                "hello world\n".getBytes(StandardCharsets.UTF_8));

        server.verify();
    }

    @Test
    void largeBinaryArtifact_uploadsAndLinks() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        // 1) Multipart upload
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess(
                        "{\"alt\":\"trace.zip\",\"url\":\"/uploads/HASH/trace.zip\","
                                + "\"markdown\":\"[trace.zip](/uploads/HASH/trace.zip)\"}",
                        MediaType.APPLICATION_JSON));

        // 2) Comment with the returned markdown
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/9/notes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("**trace.zip**"),
                        org.hamcrest.Matchers.containsString("[trace.zip](/uploads/HASH/trace.zip)"))))
                .andRespond(withSuccess());

        byte[] payload = new byte[64 * 1024];  // 64 KiB binary, beyond the inline image cap
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        client.attachPullRequestArtifact("owner", "repo", 9L, "trace.zip", "application/zip", payload);

        server.verify();
    }

    @Test
    void uploadFailure_fallsBackToSummaryComment() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/9/notes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("SHA-256:")))
                .andRespond(withSuccess());

        byte[] payload = new byte[64 * 1024];
        client.attachPullRequestArtifact("owner", "repo", 9L, "trace.zip", "application/zip", payload);

        server.verify();
    }

    @Test
    void unknownContentType_doesNotThrow() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitlab.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"markdown\":\"[x.bin](/uploads/AAA/x.bin)\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gitlab.example.com/api/v4/projects/owner%2Frepo/merge_requests/1/notes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        byte[] payload = new byte[80 * 1024];
        client.attachPullRequestArtifact("owner", "repo", 1L, "x.bin", "!not-a-mime!", payload);

        server.verify();
        assertTrue(true, "completed without exception");
    }
}

