package org.remus.giteabot.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.gitlab.GitLabApiClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PullRequestWriteStateTest {
    static Stream<Arguments> states() {
        Stream<Arguments> githubStyle = Stream.of("gitea", "github").flatMap(provider -> Stream.of(
                Arguments.of(provider, "{\"state\":\"open\",\"merged\":false}", true),
                Arguments.of(provider, "{\"state\":\"closed\",\"merged\":true}", false),
                Arguments.of(provider, "{\"state\":\"closed\",\"merged\":false}", false),
                Arguments.of(provider, "{\"state\":\"open\",\"merged\":true}", false),
                Arguments.of(provider, "{\"state\":\"open\"}", false),
                Arguments.of(provider, "{}", false)));
        Stream<Arguments> gitlab = Stream.of("opened", "closed", "merged", "locked", "unknown")
                .map(state -> Arguments.of("gitlab", "{\"state\":\"" + state + "\"}", state.equals("opened")));
        Stream<Arguments> bitbucket = Stream.of("OPEN", "MERGED", "DECLINED", "SUPERSEDED", "unknown")
                .map(state -> Arguments.of("bitbucket", "{\"state\":\"" + state + "\"}", state.equals("OPEN")));
        return Stream.concat(githubStyle, Stream.concat(gitlab, bitbucket));
    }

    @ParameterizedTest
    @MethodSource("states")
    void checksNativeStateAtProviderEndpoint(String provider, String response, boolean open) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://git.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryApiClient client = client(provider, builder.build());
        server.expect(requestTo(endpoint(provider))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThat(client.isPullRequestOpen("owner", "repo", 42L)).isEqualTo(open);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gitea", "github", "gitlab", "bitbucket"})
    void lookupFailuresPropagate(String provider) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://git.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryApiClient client = client(provider, builder.build());
        server.expect(requestTo(endpoint(provider))).andRespond(withServerError());

        assertThatThrownBy(() -> client.isPullRequestOpen("owner", "repo", 42L))
                .isInstanceOf(RestClientException.class);

        server.verify();
    }

    private static RepositoryApiClient client(String provider, RestClient restClient) {
        return switch (provider) {
            case "gitea" -> new GiteaApiClient(restClient);
            case "github" -> new GitHubApiClient(restClient);
            case "gitlab" -> new GitLabApiClient(restClient);
            case "bitbucket" -> new BitbucketApiClient(restClient, "https://git.example");
            default -> throw new IllegalArgumentException(provider);
        };
    }

    private static String endpoint(String provider) {
        return "https://git.example" + switch (provider) {
            case "gitea" -> "/api/v1/repos/owner/repo/pulls/42";
            case "github" -> "/repos/owner/repo/pulls/42";
            case "gitlab" -> "/api/v4/projects/owner%2Frepo/merge_requests/42";
            case "bitbucket" -> "/repositories/owner/repo/pullrequests/42";
            default -> throw new IllegalArgumentException(provider);
        };
    }
}
