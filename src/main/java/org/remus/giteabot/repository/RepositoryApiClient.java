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

    void postComment(String owner, String repo, Long issueNumber, String body);

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

    // ---- Repository operations ----

    String getDefaultBranch(String owner, String repo);

    List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref);

    String getFileContent(String owner, String repo, String path, String ref);

    String getFileSha(String owner, String repo, String path, String ref);

    void createBranch(String owner, String repo, String branchName, String fromRef);

    void createOrUpdateFile(String owner, String repo, String path, String content,
                            String message, String branch, String sha);

    void deleteFile(String owner, String repo, String path, String message,
                    String branch, String sha);

    Long createPullRequest(String owner, String repo, String title, String body,
                           String head, String base);

    void deleteBranch(String owner, String repo, String branchName);
}
