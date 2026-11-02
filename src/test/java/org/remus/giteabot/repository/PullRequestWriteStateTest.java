package org.remus.giteabot.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.github.GitHubApiClient;
import org.remus.giteabot.gitlab.GitLabApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
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
        RepositoryCredentials credentials = RepositoryCredentials.of("https://git.example", "https://git.example", "");
        return switch (provider) {
            case "gitea" -> new GiteaApiClient(restClient, credentials);
            case "github" -> new GitHubApiClient(restClient, credentials);
            case "gitlab" -> new GitLabApiClient(restClient, credentials);
            case "bitbucket" -> new BitbucketApiClient(restClient, credentials);
            default -> throw new IllegalArgumentException(provider);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"gitlab", "bitbucket"})
    void detailsReturnFreshSourceAndTargetCommits(String provider) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://git.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryApiClient client = client(provider, builder.build());
        String body = provider.equals("gitlab") ? """
                {"title":"Change","description":"Details","state":"opened",
                 "source_branch":"feature/x","target_branch":"main","sha":"HEAD",
                 "diff_refs":{"head_sha":"HEAD","start_sha":"BASE","base_sha":"MERGE_BASE"}}
                """ : """
                {"title":"Change","description":"Details","state":"OPEN",
                 "source":{"branch":{"name":"feature/x"},"commit":{"hash":"HEAD"}},
                 "destination":{"branch":{"name":"main"},"commit":{"hash":"BASE"}}}
                """;
        for (String head : List.of("a".repeat(40), "b".repeat(40))) {
            server.expect(requestTo(endpoint(provider))).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(body.replace("HEAD", head).replace("MERGE_BASE", "d".repeat(40))
                            .replace("BASE", "c".repeat(40)), MediaType.APPLICATION_JSON));
        }
        for (String head : List.of("a".repeat(40), "b".repeat(40))) {
            assertThat(client.getPullRequestDetails("owner", "repo", 42L)).contains(
                    new PullRequestDetails("Change", "Details", PullRequestState.OPEN,
                            "feature/x", head, "main", "c".repeat(40)));
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"gitlab,opened,OPEN", "gitlab,merged,MERGED", "gitlab,closed,CLOSED_WITHOUT_MERGE",
            "gitlab,locked,", "gitlab,unknown,", "bitbucket,OPEN,OPEN", "bitbucket,MERGED,MERGED",
            "bitbucket,DECLINED,CLOSED_WITHOUT_MERGE", "bitbucket,SUPERSEDED,CLOSED_WITHOUT_MERGE",
            "bitbucket,unknown,"})
    void detailsMapLifecycleWithoutInventingMissingRevisions(String provider, String state, PullRequestState expected) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://git.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryApiClient client = client(provider, builder.build());
        server.expect(requestTo(endpoint(provider)))
                .andRespond(withSuccess("{\"state\":\"" + state + "\"}", MediaType.APPLICATION_JSON));
        assertThat(client.getPullRequestDetails("owner", "repo", 42L)).contains(
                new PullRequestDetails(null, null, expected, null, null, null, null));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gitlab", "bitbucket"})
    void detailsKeepEmptyResponsesEmptyAndPropagateHttpFailures(String provider) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://git.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RepositoryApiClient client = client(provider, builder.build());
        server.expect(requestTo(endpoint(provider))).andRespond(withSuccess());
        server.expect(requestTo(endpoint(provider))).andRespond(withServerError());
        assertThat(client.getPullRequestDetails("owner", "repo", 42L)).isEmpty();
        assertThatThrownBy(() -> client.getPullRequestDetails("owner", "repo", 42L))
                .isInstanceOf(RestClientException.class);
        server.verify();
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
