package org.remus.giteabot.github;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.github.model.GitHubReview;
import org.remus.giteabot.github.model.GitHubReviewComment;
import org.remus.giteabot.repository.RepositoryApiClient;
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
    public void postComment(String owner, String repo, Long issueNumber, String body) {
        log.info("Posting comment on issue/PR #{} in {}/{}", issueNumber, owner, repo);
        restClient.post()
                .uri("/repos/{owner}/{repo}/issues/{issue_number}/comments", owner, repo, issueNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
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
    public String getFileSha(String owner, String repo, String path, String ref) {
        log.info("Fetching file SHA for {}/{}/{} at ref={}", owner, repo, path, ref);
        Map<String, Object> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/")
                        .path(path)
                        .queryParam("ref", ref)
                        .build(owner, repo))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("sha")) {
            return (String) result.get("sha");
        }
        return null;
    }

    @Override
    public void createBranch(String owner, String repo, String branchName, String fromRef) {
        log.info("Creating branch '{}' from '{}' in {}/{}", branchName, fromRef, owner, repo);
        // GitHub requires the SHA of the commit, not a branch name.
        // Resolve the ref to a SHA first.
        String sha = resolveRef(owner, repo, fromRef);
        restClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .body(Map.of("ref", "refs/heads/" + branchName, "sha", sha))
                .retrieve()
                .toBodilessEntity();
        log.info("Branch '{}' created successfully", branchName);
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
    public void deleteFile(String owner, String repo, String path, String message,
                           String branch, String sha) {
        log.info("Deleting file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        restClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/")
                        .path(path)
                        .build(owner, repo))
                .body(Map.of("message", message, "branch", branch, "sha", sha))
                .retrieve()
                .toBodilessEntity();
        log.info("File {} deleted successfully", path);
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
    public void deleteBranch(String owner, String repo, String branchName) {
        log.info("Deleting branch '{}' in {}/{}", branchName, owner, repo);
        try {
            restClient.delete()
                    .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}", owner, repo, branchName)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Branch '{}' deleted successfully", branchName);
        } catch (Exception e) {
            log.warn("Failed to delete branch '{}': {}", branchName, e.getMessage());
        }
    }

    // ---- Internal helpers ----

    private String resolveRef(String owner, String repo, String ref) {
        Map<String, Object> result = restClient.get()
                .uri("/repos/{owner}/{repo}/git/ref/heads/{ref}", owner, repo, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("object") instanceof Map<?, ?> obj) {
            return (String) obj.get("sha");
        }
        // Fallback: assume the ref is already a SHA
        return ref;
    }

    // ---- Request DTOs ----

    record ReviewRequest(String body, String event) {}
    record CommentRequest(String body) {}
    record ReactionRequest(String content) {}
    record InlineReviewRequest(String body, String event, List<InlineReviewComment> comments) {}
    record InlineReviewComment(String body, String path, int line) {}
    record CreatePullRequest(String title, String body, String head, String base) {}
}
