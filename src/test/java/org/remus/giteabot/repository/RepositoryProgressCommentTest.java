package org.remus.giteabot.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.gitlab.GitLabApiClient;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RepositoryProgressCommentTest {

    @ParameterizedTest
    @EnumSource(value = RepositoryType.class, names = {"GITHUB", "GITLAB", "BITBUCKET"})
    void createsAndUpdatesTheSameComment(RepositoryType provider) {
        Fixture fixture = fixture(provider);
        fixture.server().expect(requestTo(fixture.createUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(content().json(fixture.body("Starting review")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":2147483648}"));
        fixture.server().expect(requestTo(fixture.updateUrl())).andExpect(method(fixture.updateMethod()))
                .andExpect(content().json(fixture.body("Review completed"))).andRespond(withSuccess());

        CommentId id = fixture.client().postPullRequestComment("owner", "repo", 7L, "Starting review");
        assertEquals(CommentId.NumericCommentId.of(2147483648L), id);
        fixture.client().updatePullRequestComment("owner", "repo", 7L, id, "Review completed");

        fixture.server().verify();
    }

    @ParameterizedTest
    @EnumSource(value = RepositoryType.class, names = {"GITHUB", "GITLAB", "BITBUCKET"})
    void preservesTheExistingCommentCallWithoutRequiringAResponseBody(RepositoryType provider) {
        Fixture fixture = fixture(provider);
        fixture.server().expect(requestTo(fixture.createUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(content().json(fixture.body("Existing comment")))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertNull(fixture.client().postPullRequestComment("owner", "repo", 7L, "Existing comment"));

        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void returnsNoIdWhenTheResponseHasNoUsableId(RepositoryType provider, String response) {
        Fixture fixture = fixture(provider);
        fixture.server().expect(requestTo(fixture.createUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertNull(fixture.client().postPullRequestComment("owner", "repo", 7L, "Starting review"));

        fixture.server().verify();
    }

    @ParameterizedTest
    @EnumSource(value = RepositoryType.class, names = {"GITHUB", "GITLAB", "BITBUCKET"})
    void creationErrorsReachTheOptionalProgressBoundary(RepositoryType provider) {
        Fixture fixture = fixture(provider);
        fixture.server().expect(requestTo(fixture.createUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(HttpClientErrorException.class,
                () -> fixture.client().postPullRequestComment("owner", "repo", 7L, "Starting review"));

        fixture.server().verify();
    }

    @ParameterizedTest
    @EnumSource(value = RepositoryType.class, names = {"GITHUB", "GITLAB", "BITBUCKET"})
    void updateErrorsReachTheOptionalProgressBoundary(RepositoryType provider) {
        Fixture fixture = fixture(provider);
        fixture.server().expect(requestTo(fixture.updateUrl())).andExpect(method(fixture.updateMethod()))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(HttpClientErrorException.class, () -> fixture.client()
                .updatePullRequestComment("owner", "repo", 7L, CommentId.NumericCommentId.of(2147483648L), "Review completed"));

        fixture.server().verify();
    }

    static Stream<Arguments> invalidResponses() {
        return Stream.of(RepositoryType.GITHUB, RepositoryType.GITLAB, RepositoryType.BITBUCKET)
                .flatMap(provider -> Stream.of("", "null", "{}", "{\"id\":0}", "{\"id\":-1}",
                                "{\"id\":\"42\"}", "{\"id\":42.5}", "{\"id\":9223372036854775808}")
                        .map(response -> Arguments.of(provider, response)));
    }

    private Fixture fixture(RepositoryType provider) {
        String baseUrl = switch (provider) {
            case GITHUB -> "https://api.github.com";
            case GITLAB -> "https://gitlab.example.com";
            case BITBUCKET -> "https://api.bitbucket.org/2.0";
            default -> throw new IllegalArgumentException("Unexpected test provider: " + provider);
        };
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryCredentials credentials = RepositoryCredentials.of(baseUrl, baseUrl, "test-token");
        RestClient restClient = builder.build();
        RepositoryApiClient client = switch (provider) {
            case GITHUB -> new GitHubApiClient(restClient, credentials);
            case GITLAB -> new GitLabApiClient(restClient, credentials);
            case BITBUCKET -> new BitbucketApiClient(restClient, credentials);
            default -> throw new IllegalArgumentException("Unexpected test provider: " + provider);
        };
        String createPath = switch (provider) {
            case GITHUB -> "/repos/owner/repo/issues/7/comments";
            case GITLAB -> "/api/v4/projects/owner%2Frepo/merge_requests/7/notes";
            case BITBUCKET -> "/repositories/owner/repo/pullrequests/7/comments";
            default -> throw new IllegalArgumentException("Unexpected test provider: " + provider);
        };
        String updatePath = switch (provider) {
            case GITHUB -> "/repos/owner/repo/issues/comments/2147483648";
            case GITLAB -> createPath + "/2147483648";
            case BITBUCKET -> createPath + "/2147483648";
            default -> throw new IllegalArgumentException("Unexpected test provider: " + provider);
        };
        return new Fixture(client, server, baseUrl + createPath, baseUrl + updatePath,
                provider == RepositoryType.GITHUB ? HttpMethod.PATCH : HttpMethod.PUT,
                provider == RepositoryType.BITBUCKET);
    }

    private record Fixture(RepositoryApiClient client, MockRestServiceServer server,
                           String createUrl, String updateUrl, HttpMethod updateMethod, boolean bitbucket) {
        String body(String text) {
            return bitbucket ? "{\"content\":{\"raw\":\"" + text + "\"}}" : "{\"body\":\"" + text + "\"}";
        }
    }
}
