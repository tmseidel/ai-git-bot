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

    private final GitDiffService gitDiffService = mock(GitDiffService.class);

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://dev.azure.com", "https://dev.azure.com", "ado_pat");
    }

    /** A client whose PR-details and iterations requests answer with the given JSON. */
    private AzureDevopsApiClient client(String prJson, String iterationsJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String json = request.getURI().toString().contains("/iterations?") ? iterationsJson : prJson;
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });
        return new AzureDevopsApiClient(builder.build(), creds(), gitDiffService);
    }

    private static String pr(String targetTip, String head) {
        return "{\"pullRequestId\":42"
                + (targetTip != null ? ",\"lastMergeTargetCommit\":{\"commitId\":\"" + targetTip + "\"}" : "")
                + (head != null ? ",\"lastMergeSourceCommit\":{\"commitId\":\"" + head + "\"}" : "")
                + "}";
    }

    @Test
    void getPullRequestDiff_diffsTheLatestIterationsMergeBaseAgainstTheSourceHead() {
        // The target tip moves with unrelated commits; diffing against it would mix their
        // inverse into the patch. The latest iteration's merge base is the right old side.
        AzureDevopsApiClient client = client(pr(TARGET_TIP, HEAD_SHA),
                "{\"value\":[{\"id\":1,\"commonRefCommit\":{\"commitId\":\"" + TARGET_TIP + "\"}},"
                        + "{\"id\":2,\"commonRefCommit\":{\"commitId\":\"" + MERGE_BASE + "\"}}]}");
        when(gitDiffService.diffCommits(client, "contoso", "MyProject/my-service", MERGE_BASE, HEAD_SHA))
                .thenReturn("diff --git a/x b/x\n");

        assertEquals("diff --git a/x b/x\n",
                client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_fallsBackToTheTargetTipWithoutAMergeBase() {
        AzureDevopsApiClient client = client(pr(TARGET_TIP, HEAD_SHA), "{\"value\":[{\"id\":1}]}");
        when(gitDiffService.diffCommits(client, "contoso", "MyProject/my-service", TARGET_TIP, HEAD_SHA))
                .thenReturn("diff");

        assertEquals("diff", client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_returnsNullWhenACommitIsUnresolved() {
        AzureDevopsApiClient client = client(pr(TARGET_TIP, null), "{\"value\":[]}");

        assertNull(client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        verify(gitDiffService, never()).diffCommits(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void getPullRequestDiff_refusesACommitIdThatIsNotAFullSha() {
        // The ids become git arguments; an option-like value must never reach git.
        AzureDevopsApiClient client = client(pr(TARGET_TIP, "--upload-pack=evil"), "{\"value\":[]}");

        assertNull(client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        verify(gitDiffService, never()).diffCommits(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void getPullRequestDiff_returnsNullWhenTheIterationsRequestFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request ->
                request.getURI().toString().contains("/iterations?")
                        ? withServerError().createResponse(request)
                        : withSuccess(pr(TARGET_TIP, HEAD_SHA), MediaType.APPLICATION_JSON)
                                .createResponse(request));
        AzureDevopsApiClient client = new AzureDevopsApiClient(builder.build(), creds(), gitDiffService);

        assertNull(client.getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        verify(gitDiffService, never()).diffCommits(any(), anyString(), anyString(), any(), any());
    }
}
