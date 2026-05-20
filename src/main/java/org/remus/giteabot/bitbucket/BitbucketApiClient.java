package org.remus.giteabot.bitbucket;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.bitbucket.model.BitbucketReviewComment;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
    public void postPullRequestComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting comment on PR #{} in {}/{}", pullNumber, owner, repo);
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments",
                        owner, repo, pullNumber)
                .body(Map.of("content", Map.of("raw", body)))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    /**
     * Uploads the artifact via Bitbucket Cloud's repository downloads bucket
     * ({@code POST /repositories/{ws}/{repo}/downloads}) and posts a PR
     * comment linking to the resulting file under
     * {@code {cloneUrl}/{ws}/{repo}/downloads/{fileName}}.
     *
     * <p>Bitbucket has no per-PR attachment API; the {@code downloads}
     * endpoint is the only place to host arbitrary files. For inlineable
     * artifacts the shared {@link ArtifactCommentRenderer} is preferred
     * (better reviewer UX); only the renderer's summary-only fallback
     * (large or binary non-image artifacts) triggers a native upload.</p>
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
            String linkMarkdown = uploadRepositoryDownload(owner, repo, fileName, contentType, payload);
            int byteLen = payload == null ? 0 : payload.length;
            postPullRequestComment(owner, repo, pullNumber,
                    ArtifactUploadSupport.buildLinkComment(fileName, byteLen, linkMarkdown));
        } catch (RuntimeException e) {
            log.warn("Bitbucket native artifact upload for PR #{} in {}/{} failed: {} — falling back to summary comment",
                    pullNumber, owner, repo, e.toString());
            postPullRequestComment(owner, repo, pullNumber, rendered.markdown());
        }
    }

    /**
     * Calls the Bitbucket downloads endpoint and returns a Markdown link to
     * the uploaded file. Visible for tests.
     */
    String uploadRepositoryDownload(String owner, String repo, String fileName,
                                    String contentType, byte[] payload) {
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
        form.add("files", filePart);

        restClient.post()
                .uri("/repositories/{workspace}/{repo}/downloads", owner, repo)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toBodilessEntity();

        String downloadUrl = buildDownloadUrl(owner, repo, safeName);
        return ArtifactUploadSupport.linkMarkdown(safeName, downloadUrl);
    }

    private String buildDownloadUrl(String workspace, String repo, String fileName) {
        String base = credentials.cloneUrl();
        if (base == null || base.isBlank()) {
            base = "https://bitbucket.org";
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/" + workspace + "/" + repo + "/downloads/" + urlEncodePath(fileName);
    }

    private static String urlEncodePath(String s) {
        // Encode but keep typical safe punctuation in download filenames.
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
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
        restClient.post()
                .uri("/repositories/{workspace}/{repo}/issues/{issue_id}/comments",
                        owner, repo, issueNumber)
                .body(Map.of("content", Map.of("raw", body)))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getIssueComments(String owner, String repo, Long issueNumber) {
        log.info("Fetching comments for issue #{} in {}/{}", issueNumber, owner, repo);
        Map<String, Object> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/issues/{issue_id}/comments")
                        .queryParam("pagelen", 50)
                        .build(owner, repo, issueNumber))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.get("values") instanceof List<?> values) {
            return values.stream()
                    .filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value)
                    .toList();
        }
        return List.of();
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

    // ---- Internal helpers ----

    // ---- CI workflow operations (M6) ----

    @Override
    public String dispatchWorkflow(WorkflowDispatchRequest request) {
        log.info("Triggering Bitbucket Pipelines custom pipeline '{}' on branch {} for {}/{}",
                request.workflowRef(), request.gitRef(), request.owner(), request.repo());

        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("target", Map.of(
                "type", "pipeline_ref_target",
                "ref_type", "branch",
                "ref_name", request.gitRef(),
                "selector", Map.of("type", "custom", "pattern", request.workflowRef())
        ));
        if (!request.inputs().isEmpty()) {
            List<Map<String, Object>> variables = new java.util.ArrayList<>();
            request.inputs().forEach((k, v) -> variables.add(Map.of(
                    "key", k, "value", v, "secured", false
            )));
            body.put("variables", variables);
        }
        Map<String, Object> response = restClient.post()
                .uri("/2.0/repositories/{owner}/{repo}/pipelines/", request.owner(), request.repo())
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null) {
            throw new IllegalStateException(
                    "Bitbucket Pipelines accepted the trigger but returned no body for " + request.owner() + "/" + request.repo());
        }
        Object uuid = response.get("uuid");
        if (uuid == null) {
            throw new IllegalStateException(
                    "Bitbucket Pipelines accepted the trigger but returned no uuid for " + request.owner() + "/" + request.repo());
        }
        return uuid.toString();
    }

    @Override
    public WorkflowRunStatus getWorkflowRun(String owner, String repo, String runId) {
        try {
            Map<String, Object> pipeline = restClient.get()
                    .uri("/2.0/repositories/{owner}/{repo}/pipelines/{uuid}", owner, repo, runId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (pipeline == null) return WorkflowRunStatus.NOT_FOUND;
            Object stateObj = pipeline.get("state");
            String name = null;
            String resultName = null;
            if (stateObj instanceof Map<?, ?> state) {
                Object nameVal = state.get("name");
                if (nameVal != null) name = nameVal.toString();
                Object result = state.get("result");
                if (result instanceof Map<?, ?> resultMap && resultMap.get("name") != null) {
                    resultName = resultMap.get("name").toString();
                }
            }
            return mapBitbucketStatus(name, resultName);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return WorkflowRunStatus.NOT_FOUND;
        }
    }

    public static WorkflowRunStatus mapBitbucketStatus(String state, String result) {
        if (state == null) return WorkflowRunStatus.NOT_FOUND;
        return switch (state) {
            case "PENDING" -> WorkflowRunStatus.QUEUED;
            case "IN_PROGRESS", "HALTED", "BUILDING" -> WorkflowRunStatus.IN_PROGRESS;
            case "COMPLETED" -> "SUCCESSFUL".equalsIgnoreCase(result)
                    ? WorkflowRunStatus.COMPLETED_SUCCESS
                    : WorkflowRunStatus.COMPLETED_FAILURE;
            default -> WorkflowRunStatus.IN_PROGRESS;
        };
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
