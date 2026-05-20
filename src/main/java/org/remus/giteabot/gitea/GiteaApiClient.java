package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.remus.giteabot.repository.ArtifactCommentRenderer;
import org.remus.giteabot.repository.ArtifactUploadSupport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.WorkflowDispatchRequest;
import org.remus.giteabot.repository.WorkflowRunStatus;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gitea-specific implementation of {@link RepositoryApiClient}.
 * Provides all repository operations against a Gitea server using the Gitea REST API v1.
 */
@Slf4j
public class GiteaApiClient implements RepositoryApiClient {

    private final RestClient giteaRestClient;
    private final RepositoryCredentials credentials;

    /**
     * Creates a GiteaApiClient with the given RestClient and credentials.
     *
     * @param restClient  pre-configured RestClient pointing at the Gitea API base URL
     * @param credentials the repository credentials (base URL, clone URL, token)
     */
    public GiteaApiClient(RestClient restClient, RepositoryCredentials credentials) {
        this.giteaRestClient = restClient;
        this.credentials = credentials;
    }

    @Override
    public RepositoryCredentials getCredentials() {
        return credentials;
    }

    @Override
    public String getPullRequestDiff(String owner, String repo, Long pullNumber) {
        log.info("Fetching diff for PR #{} in {}/{}", pullNumber, owner, repo);
        return giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}.diff", owner, repo, pullNumber)
                .header("Accept", "text/plain")
                .retrieve()
                .body(String.class);
    }

    @Override
    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting review comment on PR #{} in {}/{}", pullNumber, owner, repo);
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .body(new ReviewRequest(body, "COMMENT"))
                .retrieve()
                .toBodilessEntity();
        log.info("Review comment posted successfully");
    }

    @Override
    public void postPullRequestComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting comment on PR #{} in {}/{}", pullNumber, owner, repo);
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/{index}/comments", owner, repo, pullNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    /**
     * Uploads the artifact via the Gitea issue-assets endpoint
     * ({@code POST /api/v1/repos/{o}/{r}/issues/{n}/assets}) and posts a
     * comment that links to the uploaded asset's
     * {@code browser_download_url}.
     *
     * <p>Mirrors the GitLab override: inlineable images / text still go
     * through {@link ArtifactCommentRenderer} (better reviewer UX); only the
     * renderer's summary-only fallback (large or binary non-image artifacts)
     * is replaced with a native upload. Upload failures degrade gracefully
     * to the renderer summary comment.</p>
     */
    @Override
    public void attachPullRequestArtifact(String owner, String repo, Long pullNumber,
                                          String fileName, String contentType, byte[] payload) {
        ArtifactCommentRenderer.RenderedComment rendered =
                ArtifactCommentRenderer.render(fileName, contentType, payload);
        if (rendered.mode() != ArtifactCommentRenderer.RenderMode.SUMMARY_ONLY) {
            postPullRequestComment(owner, repo, pullNumber, rendered.markdown());
            return;
        }
        try {
            String linkMarkdown = uploadIssueAsset(owner, repo, pullNumber, fileName, contentType, payload);
            int byteLen = payload == null ? 0 : payload.length;
            postPullRequestComment(owner, repo, pullNumber,
                    ArtifactUploadSupport.buildLinkComment(fileName, byteLen, linkMarkdown));
        } catch (RuntimeException e) {
            log.warn("Gitea native artifact upload for PR #{} in {}/{} failed: {} — falling back to summary comment",
                    pullNumber, owner, repo, e.toString());
            postPullRequestComment(owner, repo, pullNumber, rendered.markdown());
        }
    }

    /**
     * Calls the Gitea issue-assets endpoint and returns a Markdown link to
     * the uploaded asset's {@code browser_download_url}. Visible for tests.
     */
    String uploadIssueAsset(String owner, String repo, Long issueNumber,
                            String fileName, String contentType, byte[] payload) {
        String safeName = (fileName == null || fileName.isBlank()) ? "artifact.bin" : fileName.trim();
        MediaType mediaType = parseMediaType(contentType);

        ByteArrayResource fileResource = new ByteArrayResource(payload == null ? new byte[0] : payload) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, fileHeaders);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("attachment", filePart);

        Map<String, Object> response = giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/{index}/assets", owner, repo, issueNumber)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null) {
            throw new IllegalStateException("Gitea issue-assets endpoint returned empty body");
        }
        Object url = response.get("browser_download_url");
        if (url instanceof String u && !u.isBlank()) {
            return ArtifactUploadSupport.linkMarkdown(safeName, u);
        }
        throw new IllegalStateException("Gitea issue-assets response missing 'browser_download_url'");
    }

    private static MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @Override
    public void postIssueComment(String owner, String repo, Long issueNumber, String body) {
        log.info("Posting comment on issue #{} in {}/{}", issueNumber, owner, repo);
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/{index}/comments", owner, repo, issueNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    @Override
    public List<Map<String, Object>> getIssueComments(String owner, String repo, Long issueNumber) {
        log.info("Fetching comments for issue #{} in {}/{}", issueNumber, owner, repo);
        List<Map<String, Object>> comments = giteaRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/repos/{owner}/{repo}/issues/{index}/comments")
                        .queryParam("limit", 50)
                        .build(owner, repo, issueNumber))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? comments : List.of();
    }

    @Override
    public void addReaction(String owner, String repo, Long commentId, String reaction) {
        log.info("Adding '{}' reaction to comment #{} in {}/{}", reaction, commentId, owner, repo);
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/comments/{id}/reactions", owner, repo, commentId)
                .body(new ReactionRequest(reaction))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body) {
        log.info("Posting inline review comment on PR #{} in {}/{} at {}:{}", pullNumber, owner, repo, filePath, line);
        var request = new InlineReviewRequest("", "COMMENT",
                List.of(new InlineReviewComment(body, line, filePath)));
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Inline review comment posted successfully");
    }

    @Override
    public List<Review> getReviews(String owner, String repo, Long pullNumber) {
        log.info("Fetching reviews for PR #{} in {}/{}", pullNumber, owner, repo);
        List<GiteaReview> reviews = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return reviews != null ? List.copyOf(reviews) : List.of();
    }

    @Override
    public List<ReviewComment> getReviewComments(String owner, String repo, Long pullNumber,
                                                       Long reviewId) {
        log.info("Fetching comments for review #{} on PR #{} in {}/{}", reviewId, pullNumber, owner, repo);
        List<GiteaReviewComment> comments = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments",
                        owner, repo, pullNumber, reviewId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? List.copyOf(comments) : List.of();
    }

    // ---- PR context enrichment ----

    @Override
    public List<Map<String, Object>> getPullRequestCommits(String owner, String repo, Long pullNumber) {
        log.info("Fetching commits for PR #{} in {}/{}", pullNumber, owner, repo);
        List<Map<String, Object>> commits = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/commits", owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return commits != null ? commits : List.of();
    }

    @Override
    public Map<String, Object> getIssueDetails(String owner, String repo, Long issueNumber) {
        log.info("Fetching issue #{} in {}/{}", issueNumber, owner, repo);
        Map<String, Object> issue = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/issues/{index}", owner, repo, issueNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issue != null ? issue : Map.of();
    }

    @Override
    public List<Map<String, Object>> searchIssues(String owner, String repo, String query) {
        log.info("Searching issues in {}/{} for '{}'", owner, repo, query);
        List<Map<String, Object>> issues = giteaRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/repos/{owner}/{repo}/issues")
                        .queryParam("state", "all")
                        .queryParam("q", query)
                        .build(owner, repo))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issues != null ? issues : List.of();
    }

    // ---- Repository operations for the issue implementation agent ----

    @Override
    public String getDefaultBranch(String owner, String repo) {
        log.info("Fetching default branch for {}/{}", owner, repo);
        Map<String, Object> repoInfo = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (repoInfo != null && repoInfo.containsKey("default_branch")) {
            return (String) repoInfo.get("default_branch");
        }
        return "main";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref) {
        log.info("Fetching repository tree for {}/{} at ref={}", owner, repo, ref);
        Map<String, Object> result = giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/git/trees/{ref}?recursive=true", owner, repo, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("tree")) {
            return (List<Map<String, Object>>) result.get("tree");
        }
        return List.of();
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String ref) {
        log.info("Fetching file content for {}/{}/{} at ref={}", owner, repo, path, ref);
        // Use the raw endpoint to get full file content without base64 encoding or size limits.
        // Build URI manually to avoid Spring encoding slashes in the file path.
        String content = giteaRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/repos/{owner}/{repo}/raw/")
                        .path(path)
                        .queryParam("ref", ref)
                        .build(owner, repo))
                .retrieve()
                .body(String.class);
        return content != null ? content : "";
    }



    @Override
    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String message, String branch, String sha) {
        log.info("Creating/updating file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

        if (sha != null) {
            giteaRestClient.put()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/repos/{owner}/{repo}/contents/")
                            .path(path)
                            .build(owner, repo))
                    .body(new UpdateFileRequest(base64Content, message, branch, sha))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            giteaRestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/repos/{owner}/{repo}/contents/")
                            .path(path)
                            .build(owner, repo))
                    .body(new CreateFileRequest(base64Content, message, branch))
                    .retrieve()
                    .toBodilessEntity();
        }
        log.info("File {} committed successfully", path);
    }


    @Override
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base) {
        log.info("Creating pull request '{}' in {}/{} from {} to {}", title, owner, repo, head, base);
        Map<String, Object> result = giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls", owner, repo)
                .body(new CreatePullRequest(title, body, head, base))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Long prNumber = null;
        if (result != null && result.containsKey("number")) {
            prNumber = ((Number) result.get("number")).longValue();
        }
        log.info("Pull request created: #{}", prNumber);
        return prNumber;
    }

    @Override
    public Long createIssue(String owner, String repo, String title, String body) {
        log.info("Creating issue '{}' in {}/{}", title, owner, repo);
        Map<String, Object> result = giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/issues", owner, repo)
                .body(new CreateIssue(title, body))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("number") instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    // ---- CI workflow operations (M6) ----

    @Override
    public String dispatchWorkflow(WorkflowDispatchRequest request) {
        log.info("Dispatching Gitea Actions workflow '{}' on ref {} for {}/{}",
                request.workflowRef(), request.gitRef(), request.owner(), request.repo());
        // See GitHubApiClient.dispatchWorkflow for the correlation strategy —
        // Gitea Actions mirrors the GitHub Actions REST shape so the same
        // (event=workflow_dispatch [, branch]) filter applies.
        String branch = org.remus.giteabot.github.GitHubApiClient.deriveBranchFilter(request.gitRef());
        java.util.Set<Long> existing = listMatchingRunIds(
                request.owner(), request.repo(), request.workflowRef(), branch);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("ref", request.gitRef());
        if (!request.inputs().isEmpty()) {
            body.put("inputs", request.inputs());
        }
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches",
                        request.owner(), request.repo(), request.workflowRef())
                .body(body)
                .retrieve()
                .toBodilessEntity();
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            Long candidate = resolveNewRunId(
                    request.owner(), request.repo(), request.workflowRef(), branch, existing);
            if (candidate != null) {
                if (branch == null) {
                    log.warn("Resolved Gitea Actions run id={} for workflow '{}' on ref {} without"
                                    + " branch correlation (non-branch ref). In high-concurrency repos"
                                    + " this may attach to a different dispatch.",
                            candidate, request.workflowRef(), request.gitRef());
                }
                return String.valueOf(candidate);
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while resolving new Gitea run id", e);
            }
        }
        throw new IllegalStateException(
                "Gitea Actions accepted the dispatch but no new run for workflow '"
                        + request.workflowRef() + "' appeared within 15s. Make sure Gitea Actions (≥ 1.21) is enabled.");
    }

    @Override
    public WorkflowRunStatus getWorkflowRun(String owner, String repo, String runId) {
        try {
            Map<String, Object> run = giteaRestClient.get()
                    .uri("/api/v1/repos/{owner}/{repo}/actions/runs/{run_id}", owner, repo, runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (run == null) return WorkflowRunStatus.NOT_FOUND;
            String status = run.get("status") == null ? null : String.valueOf(run.get("status"));
            String conclusion = run.get("conclusion") == null ? null : String.valueOf(run.get("conclusion"));
            // Gitea Actions reuses the GitHub vocabulary (queued / in_progress / completed).
            return org.remus.giteabot.github.GitHubApiClient.mapGitHubStatus(status, conclusion);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return WorkflowRunStatus.NOT_FOUND;
        }
    }

    private java.util.Set<Long> listMatchingRunIds(
            String owner, String repo, String workflow, String branch) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Map<String, Object> run : listRecentRuns(owner, repo, workflow, branch)) {
            Object id = run.get("id");
            if (id instanceof Number n) ids.add(n.longValue());
            else if (id != null) {
                try { ids.add(Long.parseLong(id.toString())); } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }

    private Long resolveNewRunId(
            String owner, String repo, String workflow, String branch,
            java.util.Set<Long> existing) {
        Long bestId = null;
        for (Map<String, Object> run : listRecentRuns(owner, repo, workflow, branch)) {
            Object idObj = run.get("id");
            Long id;
            if (idObj instanceof Number n) {
                id = n.longValue();
            } else if (idObj == null) {
                continue;
            } else {
                try { id = Long.parseLong(idObj.toString()); }
                catch (NumberFormatException e) { continue; }
            }
            if (existing.contains(id)) continue;
            if (branch != null) {
                Object headBranch = run.get("head_branch");
                if (headBranch != null && !branch.equals(String.valueOf(headBranch))) continue;
            }
            if (bestId == null || id < bestId) bestId = id;
        }
        return bestId;
    }

    private List<Map<String, Object>> listRecentRuns(
            String owner, String repo, String workflow, String branch) {
        try {
            Map<String, Object> response = giteaRestClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder
                                .path("/api/v1/repos/{owner}/{repo}/actions/workflows/{workflow}/runs")
                                .queryParam("limit", 10)
                                .queryParam("event", "workflow_dispatch");
                        if (branch != null) b.queryParam("branch", branch);
                        return b.build(owner, repo, workflow);
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object runs = response.get("workflow_runs");
            if (runs instanceof List<?> list) {
                List<Map<String, Object>> out = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) m;
                        out.add(typed);
                    }
                }
                return out;
            }
            return List.of();
        } catch (Exception e) {
            log.debug("Could not list Gitea Actions runs for {}/{} workflow={} branch={}: {}",
                    owner, repo, workflow, branch, e.getMessage());
            return List.of();
        }
    }

    record ReviewRequest(String body, String event) {}
    record CommentRequest(String body) {}
    record ReactionRequest(String content) {}
    record InlineReviewRequest(String body, String event, List<InlineReviewComment> comments) {}
    record InlineReviewComment(String body, @com.fasterxml.jackson.annotation.JsonProperty("new_position") int newPosition, String path) {}
    record CreateFileRequest(String content, String message, String branch) {}
    record UpdateFileRequest(String content, String message, String branch, String sha) {}
    record CreatePullRequest(String title, String body, String head, String base) {}
    record CreateIssue(String title, String body) {}
}
