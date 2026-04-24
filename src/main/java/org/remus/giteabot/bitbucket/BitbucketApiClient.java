package org.remus.giteabot.bitbucket;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.bitbucket.model.BitbucketReviewComment;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Bitbucket Cloud implementation of {@link RepositoryApiClient}.
 * Provides all repository operations against Bitbucket Cloud using the REST API 2.0.
 * <p>
 * API documentation: <a href="https://developer.atlassian.com/cloud/bitbucket/rest/intro/">Bitbucket Cloud REST API</a>
 */
@Slf4j
public class BitbucketApiClient implements RepositoryApiClient {

    private final RestClient restClient;
    private final RepositoryCredentials credentials;

    /**
     * Creates a BitbucketApiClient with the given RestClient and credentials.
     *
     * @param restClient  pre-configured RestClient pointing at the Bitbucket API base URL
     * @param credentials the repository credentials (base URL, clone URL, username, token)
     */
    public BitbucketApiClient(RestClient restClient, RepositoryCredentials credentials) {
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
        try {
            // Bitbucket Cloud diff endpoint returns a 302 redirect to the actual diff content.
            // We need an HttpClient that follows redirects and accepts text/plain.
            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            String authHeader = buildAuthorizationHeader();
            String diff = RestClient.builder()
                    .baseUrl(credentials.baseUrl())
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader("Authorization", authHeader)
                    .build()
                    .get()
                    .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/diff",
                            owner, repo, pullNumber)
                    .header("Accept", "text/plain")
                    .retrieve()
                    .body(String.class);
            log.debug("Diff response length: {}", diff != null ? diff.length() : 0);
            return diff;
        } catch (Exception e) {
            log.error("Failed to fetch diff for PR #{} in {}/{}: {}", pullNumber, owner, repo, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Build the Authorization header from the credentials.
     * Uses Basic auth with username:token for App Passwords, or Bearer for API tokens.
     */
    String buildAuthorizationHeader() {
        String token = credentials.token();
        if (token == null || token.isBlank()) {
            return "";
        }

        // App Password with separate username
        if (credentials.hasUsername()) {
            String combined = credentials.username() + ":" + token;
            return "Basic " + Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
        }

        // Token already contains username:password
        if (token.contains(":")) {
            return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        }

        return "Bearer " + token;
    }

    @Override
    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting review comment on PR #{} in {}/{}", pullNumber, owner, repo);
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments",
                        owner, repo, pullNumber)
                .body(Map.of("content", Map.of("raw", body)))
                .retrieve()
                .toBodilessEntity();
        log.info("Review comment posted successfully");
    }

    @Override
    public void postComment(String owner, String repo, Long issueNumber, String body) {
        // Bitbucket Cloud doesn't have a separate issue comment API in the same way.
        // PR comments are posted via the pullrequests endpoint.
        log.info("Posting comment on PR #{} in {}/{}", issueNumber, owner, repo);
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments",
                        owner, repo, issueNumber)
                .body(Map.of("content", Map.of("raw", body)))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    @Override
    public void addReaction(String owner, String repo, Long commentId, String reaction) {
        // Bitbucket Cloud does not support reactions on comments.
        log.debug("Reactions not supported on Bitbucket Cloud, ignoring reaction '{}' on comment #{}",
                reaction, commentId);
    }

    @Override
    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body) {
        log.info("Posting inline review comment on PR #{} in {}/{} at {}:{}",
                pullNumber, owner, repo, filePath, line);
        var commentBody = Map.of(
                "content", Map.of("raw", body),
                "inline", Map.of("path", filePath, "to", line)
        );
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments",
                        owner, repo, pullNumber)
                .body(commentBody)
                .retrieve()
                .toBodilessEntity();
        log.info("Inline review comment posted successfully");
    }

    @Override
    public List<Review> getReviews(String owner, String repo, Long pullNumber) {
        log.info("Fetching reviews (activity) for PR #{} in {}/{}", pullNumber, owner, repo);
        // Bitbucket Cloud uses activity endpoint; we look for approval activities.
        // For simplicity, we return an empty list as Bitbucket has no direct review equivalent.
        // The bot primarily needs comment-based interactions which work through the comment endpoints.
        return List.of();
    }

    @Override
    public List<ReviewComment> getReviewComments(String owner, String repo,
                                                 Long pullNumber, Long reviewId) {
        log.info("Fetching comments for PR #{} in {}/{}", pullNumber, owner, repo);
        // Bitbucket doesn't have review IDs; fetch all PR comments instead.
        List<BitbucketReviewComment> comments = restClient.get()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments",
                        owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? List.copyOf(comments) : List.of();
    }

    // ---- PR context enrichment ----

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPullRequestCommits(String owner, String repo, Long pullNumber) {
        log.info("Fetching commits for PR #{} in {}/{}", pullNumber, owner, repo);
        Map<String, Object> result = restClient.get()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/commits",
                        owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("values")) {
            return (List<Map<String, Object>>) result.get("values");
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIssueDetails(String owner, String repo, Long issueNumber) {
        log.info("Fetching issue #{} in {}/{}", issueNumber, owner, repo);
        Map<String, Object> issue = restClient.get()
                .uri("/repositories/{workspace}/{repo}/issues/{issue_id}",
                        owner, repo, issueNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issue != null ? issue : Map.of();
    }

    // ---- Repository operations ----

    @Override
    public String getDefaultBranch(String owner, String repo) {
        log.info("Fetching default branch for {}/{}", owner, repo);
        Map<String, Object> result = restClient.get()
                .uri("/repositories/{workspace}/{repo}", owner, repo)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("mainbranch") instanceof Map<?, ?> mainbranch) {
            return (String) mainbranch.get("name");
        }
        return "main";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref) {
        log.info("Fetching repository tree for {}/{} at ref={}", owner, repo, ref);
        Map<String, Object> result = restClient.get()
                .uri("/repositories/{workspace}/{repo}/src/{ref}/?max_depth=100&pagelen=100",
                        owner, repo, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("values")) {
            return (List<Map<String, Object>>) result.get("values");
        }
        return List.of();
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String ref) {
        log.info("Fetching file content for {}/{}/{} at ref={}", owner, repo, path, ref);
        // Build URI manually to avoid Spring encoding slashes in the file path.
        String content = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/src/{ref}/")
                        .path(path)
                        .build(owner, repo, ref))
                .header("Accept", "text/plain")
                .retrieve()
                .body(String.class);
        return content != null ? content : "";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getFileSha(String owner, String repo, String path, String ref) {
        log.info("Fetching file SHA for {}/{}/{} at ref={}", owner, repo, path, ref);
        Map<String, Object> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/src/{ref}/")
                        .path(path)
                        .queryParam("format", "meta")
                        .build(owner, repo, ref))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("commit")) {
            Map<String, Object> commit = (Map<String, Object>) result.get("commit");
            return (String) commit.get("hash");
        }
        return null;
    }

    @Override
    public void createBranch(String owner, String repo, String branchName, String fromRef) {
        log.info("Creating branch '{}' from '{}' in {}/{}", branchName, fromRef, owner, repo);
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/refs/branches", owner, repo)
                .body(Map.of(
                        "name", branchName,
                        "target", Map.of("hash", resolveRef(owner, repo, fromRef))
                ))
                .retrieve()
                .toBodilessEntity();
        log.info("Branch '{}' created successfully", branchName);
    }

    @Override
    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String message, String branch, String sha) {
        log.info("Creating/updating file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        // Bitbucket uses the src endpoint with form data for file operations.
        // Use a multipart-like approach with the commit endpoint.
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/src", owner, repo)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(String.format("message=%s&branch=%s&%s=%s",
                        urlEncode(message), urlEncode(branch), urlEncode(path), urlEncode(content)))
                .retrieve()
                .toBodilessEntity();
        log.info("File {} committed successfully", path);
    }

    @Override
    public void deleteFile(String owner, String repo, String path, String message,
                           String branch, String sha) {
        log.info("Deleting file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/src", owner, repo)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(String.format("message=%s&branch=%s&files=%s",
                        urlEncode(message), urlEncode(branch), urlEncode(path)))
                .retrieve()
                .toBodilessEntity();
        log.info("File {} deleted successfully", path);
    }

    @Override
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base) {
        log.info("Creating pull request '{}' in {}/{} from {} to {}", title, owner, repo, head, base);
        Map<String, Object> result = restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests", owner, repo)
                .body(Map.of(
                        "title", title,
                        "description", body != null ? body : "",
                        "source", Map.of("branch", Map.of("name", head)),
                        "destination", Map.of("branch", Map.of("name", base)),
                        "close_source_branch", true
                ))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Long prId = null;
        if (result != null && result.containsKey("id")) {
            prId = ((Number) result.get("id")).longValue();
        }
        log.info("Pull request created: #{}", prId);
        return prId;
    }

    @Override
    public void deleteBranch(String owner, String repo, String branchName) {
        log.info("Deleting branch '{}' in {}/{}", branchName, owner, repo);
        try {
            restClient.delete()
                    .uri("/repositories/{workspace}/{repo}/refs/branches/{branch}",
                            owner, repo, branchName)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Branch '{}' deleted successfully", branchName);
        } catch (Exception e) {
            log.warn("Failed to delete branch '{}': {}", branchName, e.getMessage());
        }
    }

    // ---- Internal helpers ----

    private String resolveRef(String owner, String repo, String ref) {
        try {
            Map<String, Object> result = restClient.get()
                    .uri("/repositories/{workspace}/{repo}/refs/branches/{branch}",
                            owner, repo, ref)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (result != null && result.get("target") instanceof Map<?, ?> target) {
                return (String) target.get("hash");
            }
        } catch (Exception e) {
            log.debug("Could not resolve ref '{}', using as-is: {}", ref, e.getMessage());
        }
        return ref;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
