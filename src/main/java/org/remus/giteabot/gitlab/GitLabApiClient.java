package org.remus.giteabot.gitlab;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitlab.model.GitLabReview;
import org.remus.giteabot.gitlab.model.GitLabReviewComment;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GitLab-specific implementation of {@link RepositoryApiClient}.
 * Provides all repository operations against a GitLab server using the GitLab REST API v4.
 */
@Slf4j
public class GitLabApiClient implements RepositoryApiClient {

    private final RestClient gitlabRestClient;
    private final RepositoryCredentials credentials;

    /**
     * Creates a GitLabApiClient with the given RestClient and credentials.
     *
     * @param restClient  pre-configured RestClient pointing at the GitLab API base URL
     * @param credentials the repository credentials (base URL, clone URL, token)
     */
    public GitLabApiClient(RestClient restClient, RepositoryCredentials credentials) {
        this.gitlabRestClient = restClient;
        this.credentials = credentials;
    }

    @Override
    public RepositoryCredentials getCredentials() {
        return credentials;
    }

    @Override
    public String formatPullRequestReference(Long prNumber) {
        return "!" + prNumber;
    }

    @Override
    public String getPullRequestDiff(String owner, String repo, Long pullNumber) {
        log.info("Fetching diff for MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);

        // Fetch the MR to get source and target branch
        Map<String, Object> mr = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}", projectPath, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (mr == null) {
            return "";
        }

        String targetBranch = (String) mr.get("target_branch");
        String sourceBranch = (String) mr.get("source_branch");

        // Get the compare diff between the branches
        Map<String, Object> compare = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/repository/compare?from={from}&to={to}",
                        projectPath, targetBranch, sourceBranch)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (compare == null || !compare.containsKey("diffs")) {
            return "";
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) compare.get("diffs");
        return buildUnifiedDiff(diffs);
    }

    @Override
    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting note on MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/notes", projectPath, pullNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
        log.info("Note posted successfully");
    }

    @Override
    public void postComment(String owner, String repo, Long issueNumber, String body) {
        log.info("Posting note on issue #{} in {}/{}", issueNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/issues/{iid}/notes", projectPath, issueNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
        log.info("Note posted successfully");
    }

    @Override
    public void addReaction(String owner, String repo, Long commentId, String reaction) {
        // GitLab's award emoji API requires the merge request IID in addition to the note ID,
        // but the RepositoryApiClient interface only provides the comment/note ID.
        // This is a known limitation — reactions are best-effort and non-critical.
        log.debug("Skipping reaction '{}' on note #{} in {}/{}: GitLab requires MR IID which is not available",
                reaction, commentId, owner, repo);
    }

    @Override
    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body) {
        log.info("Posting inline note on MR !{} in {}/{} at {}:{}", pullNumber, owner, repo, filePath, line);
        String projectPath = encodeProjectPath(owner, repo);

        // Fetch the MR to get the diff refs needed for position
        Map<String, Object> mr = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}", projectPath, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (mr == null) {
            log.warn("Could not fetch MR !{} to create inline comment", pullNumber);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> diffRefs = (Map<String, Object>) mr.get("diff_refs");
        String baseSha = diffRefs != null ? (String) diffRefs.get("base_sha") : null;
        String headSha = diffRefs != null ? (String) diffRefs.get("head_sha") : null;
        String startSha = diffRefs != null ? (String) diffRefs.get("start_sha") : null;

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("position_type", "text");
        position.put("base_sha", baseSha);
        position.put("head_sha", headSha);
        position.put("start_sha", startSha);
        position.put("new_path", filePath);
        position.put("old_path", filePath);
        position.put("new_line", line);

        Map<String, Object> discussion = new LinkedHashMap<>();
        discussion.put("body", body);
        discussion.put("position", position);

        gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/discussions",
                        projectPath, pullNumber)
                .body(discussion)
                .retrieve()
                .toBodilessEntity();
        log.info("Inline note posted successfully");
    }

    @Override
    public List<Review> getReviews(String owner, String repo, Long pullNumber) {
        log.info("Fetching discussions for MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        List<GitLabReview> discussions = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/discussions",
                        projectPath, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return discussions != null ? List.copyOf(discussions) : List.of();
    }

    @Override
    public List<ReviewComment> getReviewComments(String owner, String repo, Long pullNumber,
                                                 Long reviewId) {
        log.info("Fetching notes for discussion on MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);

        // In GitLab, we get all discussions and find the one containing the note with reviewId
        List<GitLabReview> discussions = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/discussions",
                        projectPath, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (discussions == null) {
            return List.of();
        }

        // Find the discussion thread containing the note with the matching ID
        for (GitLabReview discussion : discussions) {
            if (discussion.getNotes() != null) {
                for (GitLabReview.GitLabNote note : discussion.getNotes()) {
                    if (reviewId.equals(note.getId())) {
                        return discussion.getNotes().stream()
                                .map(this::toReviewComment)
                                .collect(Collectors.toList());
                    }
                }
            }
        }
        return List.of();
    }

    // ---- PR context enrichment ----

    @Override
    public List<Map<String, Object>> getPullRequestCommits(String owner, String repo, Long pullNumber) {
        log.info("Fetching commits for MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        List<Map<String, Object>> commits = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/commits",
                        projectPath, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return commits != null ? commits : List.of();
    }

    @Override
    public Map<String, Object> getIssueDetails(String owner, String repo, Long issueNumber) {
        log.info("Fetching issue #{} in {}/{}", issueNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        Map<String, Object> issue = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/issues/{issue_iid}",
                        projectPath, issueNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issue != null ? issue : Map.of();
    }

    // ---- Repository operations ----

    @Override
    public String getDefaultBranch(String owner, String repo) {
        log.info("Fetching default branch for {}/{}", owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        Map<String, Object> project = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}", projectPath)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (project != null && project.containsKey("default_branch")) {
            return (String) project.get("default_branch");
        }
        return "main";
    }

    @Override
    public List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref) {
        log.info("Fetching repository tree for {}/{} at ref={}", owner, repo, ref);
        String projectPath = encodeProjectPath(owner, repo);
        List<Map<String, Object>> tree = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/repository/tree?recursive=true&ref={ref}&per_page=100",
                        projectPath, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (tree == null) {
            return List.of();
        }
        // Normalize to match the Gitea tree format (path, type fields)
        return tree.stream().map(entry -> {
            // GitLab uses "blob"/"tree", same as Gitea convention
            return (Map<String, Object>) new LinkedHashMap<>(entry);
        }).collect(Collectors.toList());
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String ref) {
        log.info("Fetching file content for {}/{}/{} at ref={}", owner, repo, path, ref);
        String projectPath = encodeProjectPath(owner, repo);
        // Use the raw endpoint to get full file content without base64 encoding or size limits.
        // GitLab expects URL-encoded file paths, which Spring's URI template expansion handles.
        String content = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/repository/files/{filePath}/raw?ref={ref}",
                        projectPath, path, ref)
                .retrieve()
                .body(String.class);
        return content != null ? content : "";
    }

    @Override
    public String getFileSha(String owner, String repo, String path, String ref) {
        log.info("Fetching file SHA for {}/{}/{} at ref={}", owner, repo, path, ref);
        String projectPath = encodeProjectPath(owner, repo);
        Map<String, Object> result = gitlabRestClient.get()
                .uri("/api/v4/projects/{projectPath}/repository/files/{filePath}?ref={ref}",
                        projectPath, path, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("blob_id")) {
            return (String) result.get("blob_id");
        }
        return null;
    }

    @Override
    public void createBranch(String owner, String repo, String branchName, String fromRef) {
        log.info("Creating branch '{}' from '{}' in {}/{}", branchName, fromRef, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/repository/branches", projectPath)
                .body(Map.of("branch", branchName, "ref", fromRef))
                .retrieve()
                .toBodilessEntity();
        log.info("Branch '{}' created successfully", branchName);
    }

    @Override
    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String message, String branch, String sha) {
        log.info("Creating/updating file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        body.put("content", base64Content);
        body.put("commit_message", message);
        body.put("encoding", "base64");
        if (sha != null) {
            body.put("last_commit_id", sha);
        }

        if (sha != null) {
            // Update existing file
            gitlabRestClient.put()
                    .uri("/api/v4/projects/{projectPath}/repository/files/{filePath}",
                            projectPath, path)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } else {
            // Create new file
            gitlabRestClient.post()
                    .uri("/api/v4/projects/{projectPath}/repository/files/{filePath}",
                            projectPath, path)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        log.info("File {} committed successfully", path);
    }

    @Override
    public void deleteFile(String owner, String repo, String path, String message,
                           String branch, String sha) {
        log.info("Deleting file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        body.put("commit_message", message);
        if (sha != null) {
            body.put("last_commit_id", sha);
        }

        gitlabRestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/v4/projects/{projectPath}/repository/files/{filePath}",
                        projectPath, path)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("File {} deleted successfully", path);
    }

    @Override
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base) {
        log.info("Creating merge request '{}' in {}/{} from {} to {}", title, owner, repo, head, base);
        String projectPath = encodeProjectPath(owner, repo);

        Map<String, Object> mrBody = new LinkedHashMap<>();
        mrBody.put("source_branch", head);
        mrBody.put("target_branch", base);
        mrBody.put("title", title);
        mrBody.put("description", body);

        Map<String, Object> result = gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/merge_requests", projectPath)
                .body(mrBody)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Long mrIid = null;
        if (result != null && result.containsKey("iid")) {
            mrIid = ((Number) result.get("iid")).longValue();
        }
        log.info("Merge request created: !{}", mrIid);
        return mrIid;
    }

    @Override
    public void deleteBranch(String owner, String repo, String branchName) {
        log.info("Deleting branch '{}' in {}/{}", branchName, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        try {
            gitlabRestClient.delete()
                    .uri("/api/v4/projects/{projectPath}/repository/branches/{branch}",
                            projectPath, branchName)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Branch '{}' deleted successfully", branchName);
        } catch (Exception e) {
            log.warn("Failed to delete branch '{}': {}", branchName, e.getMessage());
        }
    }

    // ---- Internal helpers ----

    /**
     * Builds a project path (owner/repo) for use in GitLab API URL templates.
     * GitLab accepts URL-encoded project paths instead of separate owner/repo.
     * The actual URL-encoding is handled by Spring's RestClient URI template expansion,
     * so we must NOT pre-encode here to avoid double-encoding.
     */
    static String encodeProjectPath(String owner, String repo) {
        return owner + "/" + repo;
    }

    /**
     * Builds a unified diff string from GitLab's diff response format.
     */
    private String buildUnifiedDiff(List<Map<String, Object>> diffs) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> fileDiff : diffs) {
            String oldPath = (String) fileDiff.get("old_path");
            String newPath = (String) fileDiff.get("new_path");
            String diff = (String) fileDiff.get("diff");
            Boolean newFile = (Boolean) fileDiff.get("new_file");
            Boolean deletedFile = (Boolean) fileDiff.get("deleted_file");
            Boolean renamedFile = (Boolean) fileDiff.get("renamed_file");

            sb.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append("\n");
            if (Boolean.TRUE.equals(newFile)) {
                sb.append("new file mode 100644\n");
            }
            if (Boolean.TRUE.equals(deletedFile)) {
                sb.append("deleted file mode 100644\n");
            }
            if (Boolean.TRUE.equals(renamedFile)) {
                sb.append("rename from ").append(oldPath).append("\n");
                sb.append("rename to ").append(newPath).append("\n");
            }
            sb.append("--- a/").append(oldPath).append("\n");
            sb.append("+++ b/").append(newPath).append("\n");
            if (diff != null) {
                sb.append(diff);
                if (!diff.endsWith("\n")) {
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Converts a GitLab note to a ReviewComment.
     */
    private ReviewComment toReviewComment(GitLabReview.GitLabNote note) {
        GitLabReviewComment comment = new GitLabReviewComment();
        comment.setId(note.getId());
        comment.setBody(note.getBody());
        comment.setCreatedAt(note.getCreatedAt());
        comment.setAuthor(note.getAuthor());
        return comment;
    }
}
