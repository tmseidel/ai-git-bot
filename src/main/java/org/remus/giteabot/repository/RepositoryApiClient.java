package org.remus.giteabot.repository;

import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic interface for repository operations (pull requests, reviews,
 * comments, branches, files).  Implementations exist for Gitea
 * ({@link org.remus.giteabot.gitea.GiteaApiClient}), GitHub
 * ({@link org.remus.giteabot.github.GitHubApiClient}), GitLab
 * ({@link org.remus.giteabot.gitlab.GitLabApiClient}), and Bitbucket Cloud
 * ({@link org.remus.giteabot.bitbucket.BitbucketApiClient}).
 * <p>
 * Each bot receives its own {@code RepositoryApiClient} instance, pre-configured
 * with the bot's credentials from the {@link org.remus.giteabot.admin.GitIntegration}
 * entity stored in the database.
 */
public interface RepositoryApiClient {

    /** Returns the credentials used by this client (base URL, clone URL, username, token). */
    RepositoryCredentials getCredentials();

    /** Returns the API base URL of the repository provider (e.g. {@code https://api.github.com}). */
    default String getBaseUrl() {
        return getCredentials().baseUrl();
    }

    /** Returns the web/clone URL of the repository provider (e.g. {@code https://github.com}). */
    default String getCloneUrl() {
        return getCredentials().cloneUrl();
    }

    /** Returns the authentication token used by this client. */
    default String getToken() {
        return getCredentials().token();
    }

    /**
     * Formats a pull/merge request reference for use in comments.
     * GitLab uses {@code !N} for merge requests, while Gitea, GitHub, and Bitbucket use {@code #N}.
     * Override in provider-specific clients as needed.
     */
    default String formatPullRequestReference(Long prNumber) {
        return "#" + prNumber;
    }

    // ---- Pull request operations ----

    String getPullRequestDiff(String owner, String repo, Long pullNumber);

    void postReviewComment(String owner, String repo, Long pullNumber, String body);

    default void postReviewAction(String owner, String repo, Long pullNumber, PostReviewAction action) {
        // Most providers do not support post-review state changes.
    }

    /**
     * Posts a regular top-level comment on a pull/merge request conversation.
     * <p>
     * Providers like GitHub and Gitea can often reuse the same underlying endpoint as issue comments,
     * while GitLab requires a merge-request-specific endpoint.
     */
    void postPullRequestComment(String owner, String repo, Long pullNumber, String body);

    /**
     * Attaches an artifact (test screenshot, trace, log file, …) to a pull
     * request as a Markdown-rendered comment.
     *
     * <p>The default implementation works for every provider via the existing
     * {@link #postPullRequestComment(String, String, Long, String)} endpoint
     * and is the fallback used by the {@code attach-artifact} PR-workflow
     * tool: image artifacts are inlined as a {@code data:} URI, text
     * artifacts are inlined inside a fenced code block (truncated at 64 KiB),
     * binary non-image artifacts are summarised with size + SHA-256. Provider
     * implementations may override this to push to a real attachment store
     * (GitHub Releases assets, GitLab uploads, Bitbucket downloads, …) and
     * link the resulting URL instead.</p>
     *
     * @param owner       repository owner
     * @param repo        repository name
     * @param pullNumber  target pull request number
     * @param fileName    display file name (used in the comment header / link text)
     * @param contentType MIME type, e.g. {@code image/png} or {@code text/plain}; may be {@code null}
     * @param payload     raw artifact bytes; never {@code null}
     */
    default void attachPullRequestArtifact(String owner, String repo, Long pullNumber,
                                           String fileName, String contentType, byte[] payload) {
        org.remus.giteabot.repository.ArtifactCommentRenderer.RenderedComment rendered =
                org.remus.giteabot.repository.ArtifactCommentRenderer.render(fileName, contentType, payload);
        postPullRequestComment(owner, repo, pullNumber, rendered.markdown());
    }

    /**
     * Posts a regular top-level comment on an issue.
     */
    void postIssueComment(String owner, String repo, Long issueNumber, String body);

    /**
     * Returns regular top-level comments on an issue or issue-like pull request discussion.
     * <p>
     * Implementations return provider-native maps so callers can extract common fields such as
     * {@code body}, {@code user}, {@code author}, and {@code created_at} without introducing a
     * provider-specific comment model.
     */
    default List<Map<String, Object>> getIssueComments(String owner, String repo, Long issueNumber) {
        return List.of();
    }

    void addReaction(String owner, String repo, Long commentId, String reaction);

    void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                 String filePath, int line, String body);

    List<Review> getReviews(String owner, String repo, Long pullNumber);

    List<ReviewComment> getReviewComments(String owner, String repo,
                                                     Long pullNumber, Long reviewId);

    /**
     * Returns the list of commits in a pull request.
     * Each map contains at minimum "message" (commit message) and "sha" keys.
     * Default implementation returns an empty list.
     */
    default List<Map<String, Object>> getPullRequestCommits(String owner, String repo, Long pullNumber) {
        return List.of();
    }

    /**
     * Returns issue details as a map with "title" and "body" keys.
     * Default implementation returns an empty map.
     */
    default Map<String, Object> getIssueDetails(String owner, String repo, Long issueNumber) {
        return Map.of();
    }

    default List<Map<String, Object>> searchIssues(String owner, String repo, String query) {
        return List.of();
    }

    default Long createIssue(String owner, String repo, String title, String body) {
        throw new UnsupportedOperationException("Creating issues is not supported by this repository provider");
    }

    // ---- Repository operations ----

    String getDefaultBranch(String owner, String repo);

    List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref);

    String getFileContent(String owner, String repo, String path, String ref);


    void createOrUpdateFile(String owner, String repo, String path, String content,
                            String message, String branch, String sha);

    Long createPullRequest(String owner, String repo, String title, String body,
                           String head, String base);

    // ---- CI workflow operations (M6 — CI action deployment strategy) ----

    /**
     * Triggers a provider-native CI workflow / pipeline. Implementations
     * return an opaque provider-assigned run id ({@code workflow run id} on
     * GitHub / Gitea Actions, {@code pipeline id} on GitLab CI, {@code build
     * uuid} on Bitbucket Pipelines) that the bot persists in
     * {@code deployment_handle_json} and feeds back into
     * {@link #getWorkflowRun(String, String, String)} and
     * {@link #getWorkflowRunOutputs(String, String, String)}.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException} so the bot fails fast when the
     * operator picks a {@code CI_ACTION} deployment target on a provider that
     * has not (yet) been ported.</p>
     */
    default String dispatchWorkflow(WorkflowDispatchRequest request) {
        throw new UnsupportedOperationException(
                "Workflow dispatch is not supported by this repository provider");
    }

    /**
     * Returns the current status of a workflow run / pipeline previously
     * dispatched via {@link #dispatchWorkflow(WorkflowDispatchRequest)}. Must
     * be safe to poll on a tight schedule (polling cadence is operator
     * configurable, default 15 s).
     *
     * <p>Default implementation throws
     * {@link UnsupportedOperationException}; the {@code CiActionPoller}
     * catches this and translates it into {@link WorkflowRunStatus#NOT_FOUND}
     * so the run does not get stuck in {@code WAITING_DEPLOY} forever.</p>
     */
    default WorkflowRunStatus getWorkflowRun(String owner, String repo, String runId) {
        throw new UnsupportedOperationException(
                "Workflow run lookup is not supported by this repository provider");
    }

    /**
     * Returns named outputs produced by the workflow run. Used by the
     * {@code CI_ACTION} deployment strategy to extract a {@code preview_url}
     * after the workflow has completed.
     *
     * <p>Per-provider semantics:</p>
     * <ul>
     *     <li><b>GitLab:</b> pipeline trigger variables ({@code GET
     *         /projects/:id/pipelines/:id/variables}).</li>
     *     <li><b>GitHub / Gitea / Bitbucket:</b> not exposed via REST API in a
     *         generic way; the default implementation returns an empty map and
     *         operators are expected to either echo the preview URL through
     *         the bot's {@code callbackUrl} (preferred) or rely on the
     *         {@code DeploymentTarget.previewUrlTemplate} fallback.</li>
     * </ul>
     */
    default java.util.Map<String, String> getWorkflowRunOutputs(String owner, String repo, String runId) {
        return java.util.Map.of();
    }
}
