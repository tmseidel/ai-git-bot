package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.GitDiffService;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureDevopsApiClientDiffTest {

    private static final String MERGE_BASE = "1111111111111111111111111111111111111111";
    private static final String TARGET_TIP = "2222222222222222222222222222222222222222";
    private static final String HEAD_SHA = "3333333333333333333333333333333333333333";
    private static final String OLD_HEAD = "4444444444444444444444444444444444444444";

    private final GitDiffService gitDiffService = mock(GitDiffService.class);

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://dev.azure.com", "https://dev.azure.com", "ado_pat");
    }

    /** A client whose iterations request answers with the given JSON. */
    private AzureDevopsApiClient client(String iterationsJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request ->
                withSuccess(iterationsJson, MediaType.APPLICATION_JSON).createResponse(request));
        return new AzureDevopsApiClient(builder.build(), creds(), gitDiffService);
    }

    /** One iteration; a {@code null} commit leaves its ref out, as Azure DevOps does. */
    private static String iteration(long id, String mergeBase, String source, String target) {
        return "{\"id\":" + id
                + ref("commonRefCommit", mergeBase)
                + ref("sourceRefCommit", source)
                + ref("targetRefCommit", target)
                + "}";
    }

    private static String ref(String name, String commitId) {
        return commitId != null ? ",\"" + name + "\":{\"commitId\":\"" + commitId + "\"}" : "";
    }

    private static String iterations(String... iterations) {
        return "{\"count\":" + iterations.length + ",\"value\":[" + String.join(",", iterations) + "]}";
    }

    @Test
    void getPullRequestDiff_diffsTheLatestIterationsMergeBaseAgainstItsSourceHead() {
        // The target tip moves with unrelated commits; diffing against it would mix their
        // inverse into the patch. Both ends come from the latest (highest id) iteration.
        AzureDevopsApiClient client = client(iterations(
                iteration(1, TARGET_TIP, OLD_HEAD, TARGET_TIP),
                iteration(2, MERGE_BASE, HEAD_SHA, TARGET_TIP)));
        when(gitDiffService.diffCommits(client, "contoso", "MyProject/my-service", MERGE_BASE, HEAD_SHA))
                .thenReturn("diff --git a/x b/x\n");

        assertEquals("diff --git a/x b/x\n",
                client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_picksTheLatestIterationByIdNotByPosition() {
        AzureDevopsApiClient client = client(iterations(
                iteration(2, MERGE_BASE, HEAD_SHA, TARGET_TIP),
                iteration(1, TARGET_TIP, OLD_HEAD, TARGET_TIP)));
        when(gitDiffService.diffCommits(client, "contoso", "MyProject/my-service", MERGE_BASE, HEAD_SHA))
                .thenReturn("diff");

        assertEquals("diff", client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_fallsBackToTheTargetTipWithoutAMergeBase() {
        AzureDevopsApiClient client = client(iterations(iteration(1, null, HEAD_SHA, TARGET_TIP)));
        when(gitDiffService.diffCommits(client, "contoso", "MyProject/my-service", TARGET_TIP, HEAD_SHA))
                .thenReturn("diff");

        assertEquals("diff", client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_returnsNullWithoutIterations() {
        AzureDevopsApiClient client = client(iterations());

        assertNull(client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        verify(gitDiffService, never()).diffCommits(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void getPullRequestDiff_returnsNullWhenTheIterationsRequestFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request ->
                withServerError().createResponse(request));
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), creds(), gitDiffService);

        assertNull(client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        verify(gitDiffService, never()).diffCommits(any(), anyString(), anyString(), any(), any());
    }
}
