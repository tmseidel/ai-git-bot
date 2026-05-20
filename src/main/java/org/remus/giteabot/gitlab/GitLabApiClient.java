package org.remus.giteabot.gitlab;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitlab.model.GitLabReview;
import org.remus.giteabot.gitlab.model.GitLabReviewComment;
import org.remus.giteabot.repository.ArtifactCommentRenderer;
import org.remus.giteabot.repository.ArtifactUploadSupport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.WorkflowDispatchRequest;
import org.remus.giteabot.repository.WorkflowRunStatus;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        postPullRequestComment(owner, repo, pullNumber, body);
        log.info("Note posted successfully");
    }

    @Override
    public void postReviewAction(String owner, String repo, Long pullNumber, PostReviewAction action) {
        if (action == null || action == PostReviewAction.NONE) {
            return;
        }
        if (action == PostReviewAction.APPROVE) {
            log.info("Approving MR !{} in {}/{}", pullNumber, owner, repo);
            String projectPath = encodeProjectPath(owner, repo);
            gitlabRestClient.post()
                    .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/approve", projectPath, pullNumber)
                    .retrieve()
                    .toBodilessEntity();
        } else if (action == PostReviewAction.REQUEST_CHANGES) {
            log.info("Requesting changes on MR !{} in {}/{}", pullNumber, owner, repo);
            String projectPath = encodeProjectPath(owner, repo);
            gitlabRestClient.post()
                    .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/request_changes", projectPath, pullNumber)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    @Override
    public void postPullRequestComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting merge request note on MR !{} in {}/{}", pullNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/merge_requests/{iid}/notes", projectPath, pullNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Uploads the artifact to the GitLab project's uploads bucket
     * ({@code POST /api/v4/projects/:id/uploads}) and posts a comment that
     * links to it, instead of inlining the raw bytes as a Markdown comment.
     *
     * <p>Small payloads that the shared {@link ArtifactCommentRenderer} can
     * inline as an image data-URI or a fenced code block still use that
     * representation (better reviewer UX than a download link). Only the
     * {@link ArtifactCommentRenderer.RenderMode#SUMMARY_ONLY} fallback —
     * i.e. large or binary non-image artifacts — is replaced with a native
     * upload. Upload failures degrade silently back to the renderer
     * summary comment so an outage in the uploads endpoint never breaks
     * the {@code E2ETestWorkflow}.</p>
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
            String uploadMarkdown = uploadProjectArtifact(owner, repo, fileName, contentType, payload);
            int byteLen = payload == null ? 0 : payload.length;
            postPullRequestComment(owner, repo, pullNumber,
                    ArtifactUploadSupport.buildLinkComment(fileName, byteLen, uploadMarkdown));
        } catch (RuntimeException e) {
            log.warn("GitLab native artifact upload for MR !{} in {}/{} failed: {} — falling back to summary comment",
                    pullNumber, owner, repo, e.toString());
            postPullRequestComment(owner, repo, pullNumber, rendered.markdown());
        }
    }

    /**
     * Calls the GitLab project-uploads endpoint and returns the ready-to-embed
     * Markdown link from the response. Visible for tests.
     */
    String uploadProjectArtifact(String owner, String repo, String fileName,
                                 String contentType, byte[] payload) {
        String projectPath = encodeProjectPath(owner, repo);
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
        org.springframework.http.HttpEntity<ByteArrayResource> filePart =
                new org.springframework.http.HttpEntity<>(fileResource, fileHeaders);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", filePart);

        Map<String, Object> response = gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/uploads", projectPath)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null) {
            throw new IllegalStateException("GitLab uploads endpoint returned empty body");
        }
        Object markdown = response.get("markdown");
        if (markdown instanceof String md && !md.isBlank()) {
            return md;
        }
        Object url = response.get("url");
        if (url instanceof String u && !u.isBlank()) {
            return ArtifactUploadSupport.linkMarkdown(safeName, u);
        }
        throw new IllegalStateException("GitLab uploads response missing both 'markdown' and 'url' fields");
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
    public List<Map<String, Object>> getIssueComments(String owner, String repo, Long issueNumber) {
        log.info("Fetching notes for issue #{} in {}/{}", issueNumber, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        List<Map<String, Object>> notes = gitlabRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v4/projects/{projectPath}/issues/{iid}/notes")
                        .queryParam("per_page", 50)
                        .build(projectPath, issueNumber))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return notes != null ? notes : List.of();
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

    @Override
    public List<Map<String, Object>> searchIssues(String owner, String repo, String query) {
        log.info("Searching issues in {}/{} for '{}'", owner, repo, query);
        String projectPath = encodeProjectPath(owner, repo);
        List<Map<String, Object>> issues = gitlabRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v4/projects/{projectPath}/issues")
                        .queryParam("search", query)
                        .queryParam("scope", "all")
                        .build(Map.of("projectPath", projectPath)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return issues != null ? issues : List.of();
    }

    @Override
    public Long createIssue(String owner, String repo, String title, String body) {
        log.info("Creating issue '{}' in {}/{}", title, owner, repo);
        String projectPath = encodeProjectPath(owner, repo);
        Map<String, Object> result = gitlabRestClient.post()
                .uri("/api/v4/projects/{projectPath}/issues", projectPath)
                .body(Map.of("title", title, "description", body))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("iid") instanceof Number iid) {
            return iid.longValue();
        }
        return null;
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

    // ---- CI workflow operations (M6) ----
    //
    // GitLab CI: the {workflowRef} field of {@link WorkflowDispatchRequest} is
    // the pipeline trigger token. The {owner}/{repo} pair gets URL-encoded
    // into a project path so the bot does not need to resolve numeric ids.

    @Override
    public String dispatchWorkflow(WorkflowDispatchRequest request) {
        String projectPath = encodeProject(request.owner(), request.repo());
        log.info("Triggering GitLab pipeline on ref {} for project {}", request.gitRef(), projectPath);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", request.workflowRef());
        form.add("ref", request.gitRef());
        request.inputs().forEach((k, v) -> form.add("variables[" + k + "]", v));

        Map<String, Object> response = gitlabRestClient.post()
                .uri("/api/v4/projects/{path}/trigger/pipeline", projectPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null || response.get("id") == null) {
            throw new IllegalStateException(
                    "GitLab trigger pipeline returned no id for project " + projectPath);
        }
        Object id = response.get("id");
        return id instanceof Number n ? String.valueOf(n.longValue()) : id.toString();
    }

    @Override
    public WorkflowRunStatus getWorkflowRun(String owner, String repo, String runId) {
        String projectPath = encodeProject(owner, repo);
        try {
            Map<String, Object> pipeline = gitlabRestClient.get()
                    .uri("/api/v4/projects/{path}/pipelines/{id}", projectPath, runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (pipeline == null) return WorkflowRunStatus.NOT_FOUND;
            String status = pipeline.get("status") == null ? null : String.valueOf(pipeline.get("status"));
            return mapGitLabStatus(status);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return WorkflowRunStatus.NOT_FOUND;
        }
    }

    @Override
    public Map<String, String> getWorkflowRunOutputs(String owner, String repo, String runId) {
        // GitLab exposes the trigger variables of a pipeline; the CI_ACTION
        // strategy uses these as the {preview_url} carrier when configured.
        String projectPath = encodeProject(owner, repo);
        try {
            List<Map<String, Object>> variables = gitlabRestClient.get()
                    .uri("/api/v4/projects/{path}/pipelines/{id}/variables", projectPath, runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (variables == null || variables.isEmpty()) return Map.of();
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (Map<String, Object> variable : variables) {
                Object key = variable.get("key");
                Object value = variable.get("value");
                if (key != null && value != null) {
                    result.put(key.toString(), value.toString());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not fetch GitLab pipeline variables for {}/{} id={}: {}",
                    owner, repo, runId, e.getMessage());
            return Map.of();
        }
    }

    private static String encodeProject(String owner, String repo) {
        return java.net.URLEncoder.encode(owner + "/" + repo, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static WorkflowRunStatus mapGitLabStatus(String status) {
        if (status == null) return WorkflowRunStatus.NOT_FOUND;
        return switch (status) {
            case "created", "waiting_for_resource", "preparing", "pending", "scheduled", "manual" -> WorkflowRunStatus.QUEUED;
            case "running" -> WorkflowRunStatus.IN_PROGRESS;
            case "success" -> WorkflowRunStatus.COMPLETED_SUCCESS;
            case "failed", "canceled", "cancelled", "skipped" -> WorkflowRunStatus.COMPLETED_FAILURE;
            default -> WorkflowRunStatus.IN_PROGRESS;
        };
    }
}
