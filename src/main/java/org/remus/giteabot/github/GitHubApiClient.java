package org.remus.giteabot.github;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.github.model.GitHubReview;
import org.remus.giteabot.github.model.GitHubReviewComment;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.WorkflowDispatchRequest;
import org.remus.giteabot.repository.WorkflowRunStatus;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * GitHub-specific implementation of {@link RepositoryApiClient}.
 * Provides all repository operations against a GitHub server using the GitHub REST API v3.
 */
@Slf4j
public class GitHubApiClient implements RepositoryApiClient {

    private final RestClient restClient;
    private final RepositoryCredentials credentials;

    /**
     * Creates a GitHubApiClient with the given RestClient and credentials.
     *
     * @param restClient  pre-configured RestClient pointing at the GitHub API base URL
     * @param credentials the repository credentials (base URL, clone URL, token)
     */
    public GitHubApiClient(RestClient restClient, RepositoryCredentials credentials) {
        this.restClient = restClient;
        this.credentials = credentials;
    }

    @Override
    public RepositoryCredentials getCredentials() {
        return credentials;
    }

    // ---- Pull request operations ----

    @Override
    public String getPullRequestDiff(String owner, String repo, Long pullNumber) {
        log.info("Fetching diff for PR #{} in {}/{} from baseUrl={}", pullNumber, owner, repo, credentials.baseUrl());
        log.debug("Token present: {}, tokenLength: {}", credentials.token() != null && !credentials.token().isBlank(),
                credentials.token() != null ? credentials.token().length() : 0);
        return restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}", owner, repo, pullNumber)
                .header("Accept", "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    @Override
    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting review comment on PR #{} in {}/{}", pullNumber, owner, repo);
        restClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}/reviews", owner, repo, pullNumber)
                .body(new ReviewRequest(body, "COMMENT"))
                .retrieve()
                .toBodilessEntity();
        log.info("Review comment posted successfully");
    }

    @Override
    public void postPullRequestComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting top-level comment on PR #{} in {}/{}", pullNumber, owner, repo);
        restClient.post()
                .uri("/repos/{owner}/{repo}/issues/{issue_number}/comments", owner, repo, pullNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    @Override
    public void postIssueComment(String owner, String repo, Long issueNumber, String body) {
        log.info("Posting comment on issue #{} in {}/{}", issueNumber, owner, repo);
        restClient.post()
                .uri("/repos/{owner}/{repo}/issues/{issue_number}/comments", owner, repo, issueNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    @Override
    public List<Map<String, Object>> getIssueComments(String owner, String repo, Long issueNumber) {
        log.info("Fetching comments for issue #{} in {}/{}", issueNumber, owner, repo);
        List<Map<String, Object>> comments = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/issues/{issue_number}/comments")
                        .queryParam("per_page", 50)
                        .build(owner, repo, issueNumber))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? comments : List.of();
    }

    @Override
    public void addReaction(String owner, String repo, Long commentId, String reaction) {
        log.info("Adding '{}' reaction to comment #{} in {}/{}", reaction, commentId, owner, repo);
        restClient.post()
                .uri("/repos/{owner}/{repo}/issues/comments/{comment_id}/reactions",
                        owner, repo, commentId)
                .body(new ReactionRequest(reaction))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body) {
        log.info("Posting inline review comment on PR #{} in {}/{} at {}:{}",
                pullNumber, owner, repo, filePath, line);
        var comment = new InlineReviewComment(body, filePath, line);
        var request = new InlineReviewRequest("", "COMMENT", List.of(comment));
        restClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}/reviews", owner, repo, pullNumber)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Inline review comment posted successfully");
    }

    @Override
    public List<Review> getReviews(String owner, String repo, Long pullNumber) {
        log.info("Fetching reviews for PR #{} in {}/{}", pullNumber, owner, repo);
        List<GitHubReview> reviews = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}/reviews", owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return reviews != null ? List.copyOf(reviews) : List.of();
    }

    @Override
    public List<ReviewComment> getReviewComments(String owner, String repo,
                                                 Long pullNumber, Long reviewId) {
        log.info("Fetching comments for review #{} on PR #{} in {}/{}", reviewId, pullNumber, owner, repo);
        List<GitHubReviewComment> comments = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments",
                        owner, repo, pullNumber, reviewId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? List.copyOf(comments) : List.of();
    }

    // ---- PR context enrichment ----

    @Override
    public List<Map<String, Object>> getPullRequestCommits(String owner, String repo, Long pullNumber) {
        log.info("Fetching commits for PR #{} in {}/{}", pullNumber, owner, repo);
        List<Map<String, Object>> commits = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull_number}/commits", owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return commits != null ? commits : List.of();
    }

    @Override
    public Map<String, Object> getIssueDetails(String owner, String repo, Long issueNumber) {
        log.info("Fetching issue #{} in {}/{}", issueNumber, owner, repo);
        Map<String, Object> issue = restClient.get()
                .uri("/repos/{owner}/{repo}/issues/{issue_number}", owner, repo, issueNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issue != null ? issue : Map.of();
    }

    @Override
    public List<Map<String, Object>> searchIssues(String owner, String repo, String query) {
        log.info("Searching issues in {}/{} for '{}'", owner, repo, query);
        Map<String, Object> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/issues")
                        .queryParam("q", "repo:" + owner + "/" + repo + " is:issue " + query)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Object items = result != null ? result.get("items") : null;
        if (items instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    // ---- Repository operations ----

    @Override
    public String getDefaultBranch(String owner, String repo) {
        log.info("Fetching default branch for {}/{}", owner, repo);
        Map<String, Object> repoInfo = restClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
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
        Map<String, Object> result = restClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{ref}?recursive=1", owner, repo, ref)
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
        // Use raw media type to get full file content without base64 encoding or size limits.
        // Build URI manually to avoid Spring encoding slashes in the file path.
        String content = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/")
                        .path(path)
                        .queryParam("ref", ref)
                        .build(owner, repo))
                .header("Accept", "application/vnd.github.raw+json")
                .retrieve()
                .body(String.class);
        return content != null ? content : "";
    }


    @Override
    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String message, String branch, String sha) {
        log.info("Creating/updating file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("message", message);
        body.put("content", base64Content);
        body.put("branch", branch);
        if (sha != null) {
            body.put("sha", sha);
        }

        restClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/")
                        .path(path)
                        .build(owner, repo))
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("File {} committed successfully", path);
    }


    @Override
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base) {
        log.info("Creating pull request '{}' in {}/{} from {} to {}", title, owner, repo, head, base);
        Map<String, Object> result = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
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
        Map<String, Object> result = restClient.post()
                .uri("/repos/{owner}/{repo}/issues", owner, repo)
                .body(new CreateIssue(title, body))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("number") instanceof Number number) {
            return number.longValue();
        }
        return null;
    }


    // ---- Internal helpers ----

    // ---- CI workflow operations (M6) ----

    @Override
    public String dispatchWorkflow(WorkflowDispatchRequest request) {
        log.info("Dispatching GitHub Actions workflow '{}' on ref {} for {}/{}",
                request.workflowRef(), request.gitRef(), request.owner(), request.repo());
        // Snapshot of all existing run ids matching our (workflow, event=workflow_dispatch
        // [, branch]) filter -- so we can detect the *new* run we just triggered without
        // mistaking a concurrent, unrelated dispatch for ours.
        String branch = deriveBranchFilter(request.gitRef());
        java.util.Set<Long> existing = listMatchingRunIds(
                request.owner(), request.repo(), request.workflowRef(), branch);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("ref", request.gitRef());
        if (!request.inputs().isEmpty()) {
            body.put("inputs", request.inputs());
        }
        try {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches",
                            request.owner(), request.repo(), request.workflowRef())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                // GitHub resolves the workflow file via the repository's DEFAULT branch.
                // If the file is missing there (e.g. only present on the feature branch
                // being PR'd), the dispatch endpoint returns 404 even though the file
                // exists on the requested `ref`. Surface an actionable hint so the
                // operator does not have to guess.
                throw new IllegalStateException(
                        "GitHub returned 404 when dispatching workflow '" + request.workflowRef()
                                + "' for " + request.owner() + "/" + request.repo()
                                + " on ref " + request.gitRef() + ". GitHub resolves the workflow"
                                + " file via the repository's DEFAULT branch — make sure '"
                                + request.workflowRef() + "' exists at .github/workflows/"
                                + request.workflowRef() + " on the default branch AND declares"
                                + " 'on: workflow_dispatch:'. Original response: " + e.getMessage(),
                        e);
            }
            if (status == 422) {
                // 422 typically means the workflow file exists but does not declare
                // workflow_dispatch, or required inputs are missing.
                throw new IllegalStateException(
                        "GitHub returned 422 when dispatching workflow '" + request.workflowRef()
                                + "'. The workflow file likely does not declare"
                                + " 'on: workflow_dispatch:' or is missing a required input."
                                + " Original response: " + e.getMessage(),
                        e);
            }
            throw e;
        }
        // GitHub's dispatch endpoint does not return a run id; poll briefly for
        // the next run that appeared after our trigger and matches the same
        // (workflow, event=workflow_dispatch [, branch]) filter. If we know the
        // branch we pick the *oldest* new id (almost always our own dispatch);
        // if we don't (e.g. pull-request head refs), correlation is best-effort
        // and we log a WARN so the operator can spot cross-contamination in
        // very high-concurrency repos.
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            Long candidate = resolveNewRunId(
                    request.owner(), request.repo(), request.workflowRef(), branch, existing);
            if (candidate != null) {
                if (branch == null) {
                    log.warn("Resolved GitHub Actions run id={} for workflow '{}' on ref {} without"
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
                throw new IllegalStateException("Interrupted while resolving new GitHub run id", e);
            }
        }
        throw new IllegalStateException(
                "GitHub Actions accepted the dispatch but no new run for workflow '"
                        + request.workflowRef() + "' appeared within 15s");
    }

    @Override
    public WorkflowRunStatus getWorkflowRun(String owner, String repo, String runId) {
        try {
            Map<String, Object> run = restClient.get()
                    .uri("/repos/{owner}/{repo}/actions/runs/{run_id}", owner, repo, runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (run == null) {
                return WorkflowRunStatus.NOT_FOUND;
            }
            String status = run.get("status") == null ? null : String.valueOf(run.get("status"));
            String conclusion = run.get("conclusion") == null ? null : String.valueOf(run.get("conclusion"));
            return mapGitHubStatus(status, conclusion);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return WorkflowRunStatus.NOT_FOUND;
        }
    }

    @Override
    public Map<String, String> getWorkflowRunOutputs(String owner, String repo, String runId) {
        // GitHub Actions does not surface per-job `outputs` via REST. The
        // CI_ACTION strategy falls back to the deployment-target preview-URL
        // template; workflows that need to ship a dynamic URL should POST it
        // to the bot's callbackUrl (see doc/PR_WORKFLOWS_CI_ACTIONS.md).
        return Map.of();
    }

    /**
     * Maps a Git ref to the {@code branch} query-parameter used by GitHub's
     * "list workflow runs" endpoint. Returns {@code null} when the ref does
     * not correspond to a single named branch (e.g. {@code refs/pull/N/head},
     * a raw SHA, or a tag) — in that case run-id correlation has to fall back
     * to "oldest new id matching event=workflow_dispatch".
     */
    public static String deriveBranchFilter(String gitRef) {
        if (gitRef == null || gitRef.isBlank()) return null;
        if (gitRef.startsWith("refs/heads/")) {
            return gitRef.substring("refs/heads/".length());
        }
        if (gitRef.startsWith("refs/") || gitRef.matches("[0-9a-f]{7,40}")) {
            // refs/tags/..., refs/pull/.../head, raw SHAs — not a branch
            return null;
        }
        // bare branch name
        return gitRef;
    }

    /**
     * Returns the set of run ids currently matching our (workflow,
     * {@code event=workflow_dispatch} [, branch]) filter. Used as a "before"
     * snapshot so {@link #resolveNewRunId} can detect the run we just
     * triggered without picking up an unrelated concurrent dispatch.
     */
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

    /**
     * After dispatch, pick the oldest run id that (a) was not in the
     * pre-dispatch snapshot and (b) matches our branch filter if we have one.
     * Choosing the *oldest* new id makes us prefer our own dispatch over any
     * dispatch that piled up after ours within the same poll window.
     */
    private Long resolveNewRunId(
            String owner, String repo, String workflow, String branch,
            java.util.Set<Long> existing) {
        Long bestId = null;
        for (Map<String, Object> run : listRecentRuns(owner, repo, workflow, branch)) {
            Object idObj = run.get("id");
            Long id = (idObj instanceof Number n) ? n.longValue()
                    : (idObj == null ? null : tryParseLong(idObj.toString()));
            if (id == null || existing.contains(id)) continue;
            if (branch != null) {
                Object headBranch = run.get("head_branch");
                if (headBranch != null && !branch.equals(String.valueOf(headBranch))) continue;
            }
            if (bestId == null || id < bestId) bestId = id;
        }
        return bestId;
    }

    private static Long tryParseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private List<Map<String, Object>> listRecentRuns(
            String owner, String repo, String workflow, String branch) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder
                                .path("/repos/{owner}/{repo}/actions/workflows/{workflow}/runs")
                                .queryParam("per_page", 10)
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
            log.debug("Could not list GitHub Actions runs for {}/{} workflow={} branch={}: {}",
                    owner, repo, workflow, branch, e.getMessage());
            return List.of();
        }
    }

    public static WorkflowRunStatus mapGitHubStatus(String status, String conclusion) {
        if (status == null) return WorkflowRunStatus.NOT_FOUND;
        return switch (status) {
            case "queued", "requested", "waiting", "pending" -> WorkflowRunStatus.QUEUED;
            case "in_progress" -> WorkflowRunStatus.IN_PROGRESS;
            case "completed" -> "success".equals(conclusion)
                    ? WorkflowRunStatus.COMPLETED_SUCCESS
                    : WorkflowRunStatus.COMPLETED_FAILURE;
            default -> WorkflowRunStatus.IN_PROGRESS;
        };
    }

    // ---- Request DTOs ----

    record ReviewRequest(String body, String event) {}
    record CommentRequest(String body) {}
    record ReactionRequest(String content) {}
    record InlineReviewRequest(String body, String event, List<InlineReviewComment> comments) {}
    record InlineReviewComment(String body, String path, int line) {}
    record CreatePullRequest(String title, String body, String head, String base) {}
    record CreateIssue(String title, String body) {}
}
