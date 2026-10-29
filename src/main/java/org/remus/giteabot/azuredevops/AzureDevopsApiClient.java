package org.remus.giteabot.azuredevops;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.GitDiffService;
import org.remus.giteabot.azuredevops.model.AzureDevopsReview;
import org.remus.giteabot.azuredevops.model.AzureDevopsReviewComment;
import org.remus.giteabot.azuredevops.model.Comment;
import org.remus.giteabot.azuredevops.model.CommentPosition;
import org.remus.giteabot.azuredevops.model.CommentThreadContext;
import org.remus.giteabot.azuredevops.model.ConnectionData;
import org.remus.giteabot.azuredevops.model.GitCommitRef;
import org.remus.giteabot.azuredevops.model.GitPullRequestCommentThread;
import org.remus.giteabot.azuredevops.model.GitPullRequestIteration;
import org.remus.giteabot.azuredevops.model.IdentityRef;
import org.remus.giteabot.azuredevops.model.ListResponse;
import org.remus.giteabot.azuredevops.model.ThreadCommentId;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.GitUser;
import org.remus.giteabot.repository.model.PullRequestCommit;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Azure DevOps implementation of {@link RepositoryApiClient}.
 * <p>
 * Addressing follows the project convention {@code owner = organization} and
 * {@code repo = "Project/Repository"}; see {@link AzureDevopsAddress}.
 * <p>
 * Work Items and Azure Pipelines are intentionally not implemented — those methods keep
 * their interface defaults.
 */
@Slf4j
public class AzureDevopsApiClient implements RepositoryApiClient {

    /**
     * REST API version for every documented endpoint. 6.0 is the highest version Azure
     * DevOps Server 2020 accepts; Services and newer servers accept it too.
     */
    static final String API_VERSION = "6.0";

    /** {@code connectionData} exists only as a preview API and rejects a release version. */
    static final String API_VERSION_CONNECTION_DATA = "6.0-preview.1";

    /** Account-level {@code connectionData}, answering for tokens scoped to all accessible organizations. */
    static final String ACCOUNT_CONNECTION_DATA_URL = "https://app.vssps.visualstudio.com/_apis/connectionData";

    private static final int VOTE_APPROVE = 10;
    /** "Waiting for author": the closest match to "request changes"; -10 would hard-reject. */
    private static final int VOTE_WAITING_FOR_AUTHOR = -5;

    /** A full Git object id. Anything else in a {@code ref} position is a branch name. */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");

    private final RestClient restClient;
    private final RepositoryCredentials credentials;
    private final GitDiffService gitDiffService;

    private String cachedReviewerId;

    public AzureDevopsApiClient(RestClient restClient, RepositoryCredentials credentials,
                                GitDiffService gitDiffService) {
        this.restClient = restClient;
        this.credentials = credentials;
        this.gitDiffService = gitDiffService;
    }

    @Override
    public RepositoryCredentials getCredentials() {
        return credentials;
    }

    /** Azure DevOps Git remotes only accept the PAT as a pre-emptive Basic header. */
    @Override
    public boolean usesGitAuthorizationHeader() {
        return true;
    }

    // ---- Request scoping ----

    /**
     * A URI path prefix plus the template variables it consumes, in order.
     * <p>
     * Needed because whether the organization appears in the path at all depends on how
     * the instance is addressed — see {@link #scopesOrganization}.
     */
    private record Scope(String path, Object[] vars) { }

    /**
     * Whether {@code url} already scopes {@code organization}, meaning the organization
     * must <em>not</em> be repeated as a path segment.
     * <p>
     * Azure DevOps is addressed three different ways, and only the first needs the
     * organization in the request path:
     * <pre>
     * https://dev.azure.com              + org "contoso" -&gt; /contoso/{project}/_apis/...
     * https://contoso.visualstudio.com   + org "contoso" -&gt; /{project}/_apis/...
     * https://tfs.example.com/tfs/DefaultCollection
     *                    + collection "DefaultCollection" -&gt; /{project}/_apis/...
     * </pre>
     * Repeating it in the second and third forms puts the organization where the server
     * expects a collection, which 404s every request.
     * <p>
     * The decision is derived from the configured URL rather than from a host allow-list,
     * so an Azure DevOps Server instance configured without its collection
     * ({@code https://tfs.example.com/tfs}) still gets the collection as a path segment.
     * Two shapes count as already-scoped:
     * <ul>
     *   <li>the organization as the leading label of a {@code *.visualstudio.com} host.
     *       The suffix is required: Azure DevOps Server never encodes its collection in
     *       the hostname, so without it a host like {@code tfs.example.com} serving a
     *       collection named {@code tfs} would wrongly look already-scoped.</li>
     *   <li>the organization as the URL path's last segment — an Azure DevOps Server
     *       collection, or {@code https://dev.azure.com/contoso} for a single organization.</li>
     * </ul>
     */
    private static boolean scopesOrganization(String url, String organization) {
        if (url == null || url.isBlank() || organization == null || organization.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        if (host != null && host.toLowerCase(Locale.ROOT).endsWith(".visualstudio.com")) {
            int firstDot = host.indexOf('.');
            if (firstDot > 0 && host.substring(0, firstDot).equalsIgnoreCase(organization)) {
                return true;
            }
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                return segments[i].equalsIgnoreCase(organization);
            }
        }
        return false;
    }

    /** The path prefix addressing one repository via the Git REST API. */
    private Scope repositoryScope(AzureDevopsAddress addr) {
        // org, project and name stay separate template variables: a combined
        // "Project/Repository" value is encoded to %2F and 404s.
        if (scopesOrganization(credentials.baseUrl(), addr.organization())) {
            return new Scope("/{project}/_apis/git/repositories/{name}",
                    new Object[]{addr.project(), addr.name()});
        }
        return new Scope("/{org}/{project}/_apis/git/repositories/{name}",
                new Object[]{addr.organization(), addr.project(), addr.name()});
    }

    /** The path prefix addressing the organization itself, for organization-level APIs. */
    private Scope organizationScope(String organization) {
        if (scopesOrganization(credentials.baseUrl(), organization)) {
            return new Scope("/_apis", new Object[0]);
        }
        return new Scope("/{org}/_apis", new Object[]{organization});
    }

    /** Appends trailing URI template values to a scope's leading ones. */
    private static Object[] vars(Scope scope, Object... trailing) {
        Object[] all = new Object[scope.vars().length + trailing.length];
        System.arraycopy(scope.vars(), 0, all, 0, scope.vars().length);
        System.arraycopy(trailing, 0, all, scope.vars().length, trailing.length);
        return all;
    }

    /**
     * Azure DevOps clone URLs are {@code {base}/{org}/{project}/_git/{name}} rather than
     * the {@code {base}/{owner}/{repo}.git} the interface default builds, so the path is
     * assembled here — including the same organization-scoping rule the REST paths use,
     * since a legacy or Azure DevOps Server clone URL carries the organization in its
     * host or collection prefix already.
     * <p>
     * The base URL still goes through
     * {@link RepositoryApiClient#validatedCloneBaseUrl}, which is what keeps a
     * credential-bearing URL out of the remote handed to {@code git}.
     */
    @Override
    public String getRepositoryRemote(String owner, String repo) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        String base = RepositoryApiClient.validatedCloneBaseUrl(getCloneUrl());
        String organizationSegment = scopesOrganization(base, addr.organization())
                ? "" : "/" + addr.organization();
        return base + organizationSegment + "/" + addr.project() + "/_git/" + addr.name();
    }

    // ---- Pull request operations ----

    /**
     * Diffs the pull request with {@code git} via {@link GitDiffService}: Azure DevOps
     * exposes no endpoint returning patch text.
     * <p>
     * The old side is the latest iteration's merge base ({@code commonRefCommit}), not
     * {@code lastMergeTargetCommit}: that is the target branch <em>tip</em>, and diffing
     * against it mixes the inverse of unrelated target-branch commits into the patch.
     * The tip ({@code targetRefCommit}) is only a fallback when that iteration carries no
     * merge base.
     */
    @Override
    public String getPullRequestDiff(String owner, String repo, Long pullNumber) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        DiffRange diffRange;
        try {
            diffRange = diffRange(addr, pullNumber);
        } catch (Exception e) {
            log.error("Failed to resolve diff commits for PR #{} in {}: {}",
                    pullNumber, repo, e.getMessage(), e);
            return null;
        }
        if (diffRange == null) {
            return null;
        }

        return gitDiffService.diffCommits(this, owner, repo, diffRange.baseSha, diffRange.headSha);
    }

    /**
     * The diff range of the pull request's latest iteration (highest {@code id}): its
     * {@code commonRefCommit}, or {@code targetRefCommit} without one, against its
     * {@code sourceRefCommit}. {@code null} when the pull request has no iterations.
     * @see <a href="https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-iterations/list?view=azure-devops-rest-7.2&tabs=HTTP">Rest API</a>
     */
    private DiffRange diffRange(AzureDevopsAddress addr, Long pullNumber) {

        Scope scope = repositoryScope(addr);
        ListResponse<GitPullRequestIteration> result = restClient.get()
                .uri(builder -> builder
                        .path(scope.path() + "/pullRequests/{prId}/iterations")
                        .queryParam("api-version", API_VERSION)
                        .build(vars(scope, pullNumber)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        GitCommitRef mergeBase;
        GitCommitRef headSha;
        if (result != null && !result.getValue().isEmpty()) {
            GitPullRequestIteration latest = result.getValue().stream()
                                                   .max(Comparator.comparingLong(GitPullRequestIteration::getId))
                                                   .orElseThrow();
            headSha = latest.getSourceRefCommit();
            mergeBase = latest.getCommonRefCommit();
            if (mergeBase == null) {
                mergeBase = latest.getTargetRefCommit();
            }
            return new DiffRange(mergeBase.getCommitId(), headSha.getCommitId());
        }
        return null;
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) {
            return null;
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * The repository-root-absolute form Azure DevOps addresses items by, for the items,
     * threads and pushes APIs alike. Callers supply either form: diff parsing and
     * {@link #getRepositoryTree} yield {@code "src/Foo.java"}, an Azure DevOps payload
     * {@code "/src/Foo.java"}.
     * <p>
     * A missing path is rejected rather than turned into the literal {@code "/null"},
     * which the server would answer with an opaque 404. Thrown, not logged, so it
     * surfaces where {@link AzureDevopsAddress#parse} does: both mean the caller passed
     * something it should never have passed.
     */
    private static String itemPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Azure DevOps item path must not be blank");
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Picks the {@code versionDescriptor.versionType} matching the shape of {@code ref}.
     * <p>
     * Azure DevOps defaults this to {@code branch}, so passing a commit id without it
     * makes the server look the sha up as a branch name and return nothing. Callers hand
     * over either form: {@code CodeReviewService#resolveHeadRef} yields a branch name,
     * while {@code UnitTestService} prefers the PR head sha. Only a full 40-character
     * object id is treated as a commit — a shorter hex string is far more likely to be a
     * branch than an abbreviated sha.
     */
    private static String versionType(String ref) {
        return isCommitSha(ref) ? "commit" : "branch";
    }

    private static boolean isCommitSha(String value) {
        return value != null && COMMIT_SHA.matcher(value).matches();
    }

    /**
     * Azure DevOps has no review object that comments belong to; every comment is a thread.
     * Bot replies and status messages are therefore posted already closed, so they stay
     * visible without counting as unresolved discussions.
     */
    @Override
    public CommentId postPullRequestComment(String owner, String repo, Long pullNumber,
                                       String body) {
        GitPullRequestCommentThread thread = postThread(owner, repo, pullNumber, body, GitPullRequestCommentThread.CommentThreadStatus.CLOSED);

        if (thread != null && thread.getComments() != null && !thread.getComments().isEmpty()) {
            return ThreadCommentId.of(thread.getId(), thread.getComments().getFirst().getId());
        }

        return null;
    }

    @Override
    public void updatePullRequestComment(String owner, String repo, Long pullNumber, CommentId commentId, String body) {
        // see: https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-thread-comments/update?view=azure-devops-rest-6.0

        ThreadCommentId threadCommentId = (ThreadCommentId) commentId;

        Comment comment = new Comment();
        comment.setContent(body);
        comment.setCommentType(Comment.CommentType.TEXT);

        // Not wrapped in run(): like the other providers, failures reach the caller, which
        // decides whether a lost update matters (ReviewProgress treats it as optional).
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        restClient.patch()
                .uri(builder -> builder
                        .path(scope.path() + "/pullRequests/{prId}/threads/{threadId}/comments/{commentId}")
                        .queryParam("api-version", API_VERSION)
                        .build(vars(scope, pullNumber, threadCommentId.getThreadId(), threadCommentId.getCommentId())))
                .body(comment)
                .retrieve()
                .toBodilessEntity();
    }

    /** A review summary is left active, like the inline findings it accompanies. */
    @Override
    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        postThread(owner, repo, pullNumber, body, GitPullRequestCommentThread.CommentThreadStatus.ACTIVE);
    }

    private GitPullRequestCommentThread postThread(String owner, String repo, Long pullNumber, String body,
                                                   GitPullRequestCommentThread.CommentThreadStatus status) {
        // see: https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP

        Comment comment = new Comment();
        comment.setParentCommentId(0L);
        comment.setContent(body);
        comment.setCommentType(Comment.CommentType.TEXT);

        GitPullRequestCommentThread thread = new GitPullRequestCommentThread();
        thread.setComments(List.of(comment));
        thread.setStatus(status);

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> restClient.post()
                        .uri(builder -> builder
                                .path(scope.path() + "/pullRequests/{prId}/threads")
                                .queryParam("api-version", API_VERSION)
                                .build(vars(scope, pullNumber)))
                        .body(thread)
                        .retrieve()
                        .body(GitPullRequestCommentThread.class),
                null, "post comment on PR #{} in {}", pullNumber, repo);
    }

    @Override
    public void postIssueComment(String owner, String repo, Long issueNumber, String body) {
        throw new UnsupportedOperationException("Azure DevOps work items are not supported");
    }

    @Override
    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body) {

        Comment comment = new Comment();
        comment.setParentCommentId(0L);
        comment.setContent(body);
        comment.setCommentType(Comment.CommentType.TEXT);

        CommentThreadContext context = new CommentThreadContext();
        context.setFilePath(itemPath(filePath));
        context.setRightFileStart(new CommentPosition(line, 1));
        context.setRightFileEnd(new CommentPosition(line, 1));

        GitPullRequestCommentThread thread = new GitPullRequestCommentThread();
        thread.setComments(List.of(comment));
        thread.setStatus(GitPullRequestCommentThread.CommentThreadStatus.ACTIVE);
        thread.setThreadContext(context);

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        run(() -> restClient.post()
                .uri(builder -> builder
                        .path(scope.path() + "/pullRequests/{prId}/threads")
                        .queryParam("api-version", API_VERSION)
                        .build(vars(scope, pullNumber)))
                .body(thread)
                .retrieve()
                .toBodilessEntity(),
                "post inline comment on PR #{} in {} at {}:{}", pullNumber, repo, filePath, line);
    }

    @Override
    public void postReviewAction(String owner, String repo, Long pullNumber,
                                 PostReviewAction action) {
        if (action == PostReviewAction.NONE) {
            return;
        }
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        String reviewerId = resolveReviewerId();
        if (reviewerId == null) {
            return;
        }
        int vote = action == PostReviewAction.APPROVE ? VOTE_APPROVE : VOTE_WAITING_FOR_AUTHOR;
        Scope scope = repositoryScope(addr);
        run(() -> restClient.put()
                .uri(builder -> builder
                        .path(scope.path() + "/pullRequests/{prId}/reviewers/{reviewerId}")
                        .queryParam("api-version", API_VERSION)
                        .build(vars(scope, pullNumber, reviewerId)))
                .body(Map.of("vote", vote))
                .retrieve()
                .toBodilessEntity(),
                "cast vote on PR #{} in {}", pullNumber, repo);
    }

    @Override
    public List<Review> getReviews(String owner, String repo, Long pullNumber) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/pullRequests/{prId}")
                            .queryParam("api-version", API_VERSION)
                            .build(vars(scope, pullNumber)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (result == null || !(result.get("reviewers") instanceof List<?> reviewers)) {
                return null;
            }
            List<Review> mapped = new ArrayList<>();
            for (Object entry : reviewers) {
                if (entry instanceof Map<?, ?> reviewer) {
                    mapped.add(toAzureDevopsReview(reviewer));
                }
            }
            return mapped;
        }, List.of(), "fetch reviews for PR #{} in {}", pullNumber, repo);
    }

    @SuppressWarnings("unchecked")
    private AzureDevopsReview toAzureDevopsReview(Map<?, ?> reviewer) {
        AzureDevopsReview review = new AzureDevopsReview();
        Object vote = reviewer.get("vote");
        if (vote instanceof Number n) {
            review.setVote(n.intValue());
        }
        review.setUniqueName((String) reviewer.get("uniqueName"));
        review.setDisplayName((String) reviewer.get("displayName"));
        return review;
    }

    /**
     * Returns every comment on the pull request, flattened out of its threads.
     * <p>
     * {@code reviewId} is ignored: Azure DevOps has no review object to scope comments to
     * — {@link AzureDevopsReview} is mapped from a reviewer's vote, which owns no
     * comments — so there is nothing narrower than the pull request to return.
     */
    @Override
    public List<ReviewComment> getReviewComments(String owner, String repo,
                                                 Long pullNumber, Long reviewId) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            ListResponse<GitPullRequestCommentThread> threads = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/pullRequests/{prId}/threads")
                            .queryParam("api-version", API_VERSION)
                            .build(vars(scope, pullNumber)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (threads == null || threads.getValue() == null) {
                return null;
            }
            List<ReviewComment> mapped = new ArrayList<>();
            for (GitPullRequestCommentThread thread : threads.getValue()) {
                mapped.addAll(toAzureDevopsReviewComments(thread));
            }
            return mapped;
        }, List.of(), "fetch review comments for PR #{} in {}", pullNumber, repo);
    }

    private List<ReviewComment> toAzureDevopsReviewComments(GitPullRequestCommentThread thread) {
        List<ReviewComment> mapped = new ArrayList<>();
        String path = null;
        Integer line = null;
        if (thread.getThreadContext() != null) {
            path = stripLeadingSlash(thread.getThreadContext().getFilePath());
            if (thread.getThreadContext().getRightFileStart() != null) {
                line = thread.getThreadContext().getRightFileStart().getLine();
            }
        }

        if (thread.getComments() != null) {
            for (Comment comment : thread.getComments()) {
                mapped.add(toAzureDevopsReviewComment(thread.getId(), comment, path, line));
            }
        }
        return mapped;
    }

    private AzureDevopsReviewComment toAzureDevopsReviewComment(Long threadId, Comment comment,
                                                                 String path, Integer line) {
        AzureDevopsReviewComment reviewComment = new AzureDevopsReviewComment();
        reviewComment.setId(ThreadCommentId.of(threadId, comment.getId()));

        reviewComment.setBody(comment.getContent());
        reviewComment.setPath(path);
        reviewComment.setLine(line);
        if (comment.getAuthor() != null) {
            IdentityRef author = comment.getAuthor();
            reviewComment.setUserLogin(author.getUniqueName());
            reviewComment.setUserId(author.getId());
        }
        return reviewComment;
    }

    /**
     * Resolves the file anchor of one pull-request comment thread.
     * <p>
     * Needed because the {@code ms.vss-code.git-pullrequest-comment-event} Service Hook
     * payload carries only the comment itself — {@code id}, {@code author},
     * {@code content}, {@code commentType} and {@code _links} — and never the enclosing
     * thread's {@code threadContext}. Without this lookup an inline (per-file) comment is
     * indistinguishable from a top-level one, and
     * {@code AzureDevopsWebhookHandler#handlePullRequestComment} would route every inline
     * {@code @bot} mention to the generic command path.
     *
     * @return the thread's anchor, or {@code null} when the thread has no file context
     *         (a top-level conversation comment) or could not be fetched
     */
    public InlineThreadContext getInlineThreadContext(String owner, String repo,
                                                      Long pullNumber, long threadId) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        try {
            Map<String, Object> thread = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/pullRequests/{prId}/threads/{threadId}")
                            .queryParam("api-version", API_VERSION)
                            .build(vars(scope, pullNumber, threadId)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (thread == null || !(thread.get("threadContext") instanceof Map<?, ?> ctx)) {
                return null;
            }
            if (!(ctx.get("filePath") instanceof String filePath) || filePath.isBlank()) {
                return null;
            }
            // Repository-root-relative, like every other path the application passes
            // around: the anchor ends up in a prompt ("Regarding `src/Foo.java`") and is
            // compared against diff and tree paths, which carry no leading slash.
            // postInlineReviewComment and getFileContent make it absolute again.
            String path = stripLeadingSlash(filePath);
            Integer line = null;
            if (ctx.get("rightFileStart") instanceof Map<?, ?> start
                    && start.get("line") instanceof Number n) {
                line = n.intValue();
            }
            return new InlineThreadContext(path, line);
        } catch (Exception e) {
            log.warn("Failed to fetch thread {} of PR #{} in {}: {}",
                    threadId, pullNumber, repo, e.getMessage());
            return null;
        }
    }

    /** The file anchor of an inline comment thread. */
    public record InlineThreadContext(String path, Integer line) { }

    @Override
    public void addReaction(String owner, String repo, CommentId commentId, String reaction) {
        // Azure DevOps has only comment likes and PR statuses; neither maps to reactions.
        log.debug("Reactions not supported on Azure DevOps, ignoring reaction '{}' on comment #{}", reaction, commentId);
    }

    @Override
    public List<PullRequestCommit> getPullRequestCommits(String owner, String repo,
                                                          Long pullNumber) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/pullRequests/{prId}/commits")
                            .queryParam("api-version", API_VERSION)
                            .build(vars(scope, pullNumber)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null && result.get("value") instanceof List<?> value
                    ? normalizeCommitEntries(value) : null;
        }, List.of(), "fetch commits for PR #{} in {}", pullNumber, repo);
    }

    /**
     * Translates Azure DevOps commit entries into {@link PullRequestCommit}s: Azure DevOps
     * carries the SHA as {@code commitId} and the message as {@code comment}.
     */
    private static List<PullRequestCommit> normalizeCommitEntries(List<?> commits) {
        List<PullRequestCommit> normalized = new ArrayList<>();
        for (Object entry : commits) {
            if (!(entry instanceof Map<?, ?> commit)) {
                continue;
            }
            normalized.add(new PullRequestCommit(stringOrNull(commit.get("commitId")),
                    stringOrNull(commit.get("comment"))));
        }
        return normalized;
    }

    @Override
    public Optional<PullRequestDetails> getPullRequestDetails(String owner, String repo,
                                                               Long pullNumber) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        Map<String, Object> pr = call(() -> restClient.get()
                .uri(builder -> builder
                        .path(scope.path() + "/pullRequests/{prId}")
                        .queryParam("api-version", API_VERSION)
                        .build(vars(scope, pullNumber)))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                null, "fetch PR #{} details in {}", pullNumber, repo);
        return Optional.ofNullable(pr).map(AzureDevopsApiClient::toPullRequestDetails);
    }

    /**
     * Maps an Azure DevOps pull request onto {@link PullRequestDetails}. Azure DevOps
     * reports the lifecycle as {@code status} ({@code active}, {@code completed},
     * {@code abandoned}) and the head / base as {@code sourceRefName} /
     * {@code targetRefName} plus {@code lastMergeSourceCommit} / {@code lastMergeTargetCommit}.
     */
    private static PullRequestDetails toPullRequestDetails(Map<String, Object> pr) {
        PullRequestState state = switch (String.valueOf(pr.get("status"))) {
            case "active" -> PullRequestState.OPEN;
            case "completed" -> PullRequestState.MERGED;
            case "abandoned" -> PullRequestState.CLOSED_WITHOUT_MERGE;
            default -> null;
        };
        return new PullRequestDetails(
                stringOrNull(pr.get("title")),
                stringOrNull(pr.get("description")),
                state,
                stringOrNull(pr.get("sourceRefName")),
                commitIdOf(pr.get("lastMergeSourceCommit")),
                stringOrNull(pr.get("targetRefName")),
                commitIdOf(pr.get("lastMergeTargetCommit")));
    }

    private static String commitIdOf(Object commit) {
        return commit instanceof Map<?, ?> map ? stringOrNull(map.get("commitId")) : null;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String string ? string : null;
    }

    // ---- Repository operations ----

    @Override
    public String getDefaultBranch(String owner, String repo) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path())
                            .queryParam("api-version", API_VERSION)
                            .build(scope.vars()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (result == null || !(result.get("defaultBranch") instanceof String branch)) {
                return null;
            }
            return branch.startsWith("refs/heads/")
                    ? branch.substring("refs/heads/".length())
                    : branch;
        }, "main", "fetch default branch for {}", repo);
    }

    @Override
    public List<RepositoryTreeEntry> getRepositoryTree(String owner, String repo, String ref) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/items")
                            .queryParam("recursionLevel", "full")
                            .queryParam("scopePath", "/")
                            .queryParam("versionDescriptor.versionType", versionType(ref))
                            .queryParam("versionDescriptor.version", ref)
                            .queryParam("api-version", API_VERSION)
                            .build(scope.vars()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null && result.get("value") instanceof List<?> value
                    ? normalizeTreeEntries(value) : null;
        }, List.of(), "fetch repository tree for {} at ref={}", repo, ref);
    }

    /**
     * Translates Azure DevOps item entries into {@link RepositoryTreeEntry}s. Azure DevOps
     * reports an absolute {@code path} (stripped of its leading slash here, like the other
     * providers) and marks directories with {@code isFolder} / {@code gitObjectType}.
     */
    private static List<RepositoryTreeEntry> normalizeTreeEntries(List<?> items) {
        List<RepositoryTreeEntry> normalized = new ArrayList<>();
        for (Object entry : items) {
            if (!(entry instanceof Map<?, ?> item)
                    || !(item.get("path") instanceof String rawPath)) {
                continue;
            }
            String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            if (path.isEmpty()) {
                // The scopePath root ("/") is returned as an entry of its own.
                continue;
            }
            boolean folder = Boolean.TRUE.equals(item.get("isFolder"))
                    || "tree".equals(item.get("gitObjectType"));
            normalized.add(new RepositoryTreeEntry(path, folder
                    ? RepositoryTreeEntry.Type.DIRECTORY
                    : RepositoryTreeEntry.Type.FILE));
        }
        return normalized;
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String ref) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        String itemPath = itemPath(path);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.get()
                    .uri(builder -> builder
                            .path(scope.path() + "/items")
                            .queryParam("path", itemPath)
                            .queryParam("includeContent", "true")
                            .queryParam("versionDescriptor.versionType", versionType(ref))
                            .queryParam("versionDescriptor.version", ref)
                            .queryParam("api-version", API_VERSION)
                            .build(scope.vars()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null && result.get("content") instanceof String content
                    ? content : null;
        }, "", "fetch file content for {}/{} at ref={}", repo, path, ref);
    }

    @Override
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base) {
        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);
        Scope scope = repositoryScope(addr);
        return call(() -> {
            Map<String, Object> result = restClient.post()
                    .uri(builder -> builder
                            .path(scope.path() + "/pullrequests")
                            .queryParam("api-version", API_VERSION)
                            .build(scope.vars()))
                    .body(Map.of(
                            "sourceRefName", "refs/heads/" + head,
                            "targetRefName", "refs/heads/" + base,
                            "title", title,
                            "description", body != null ? body : ""))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null && result.get("pullRequestId") instanceof Number n
                    ? n.longValue() : null;
        }, null, "create pull request '{}' in {}", title, repo);
    }

    // ---- Internal helpers ----

    /**
     * Runs a REST request, returning {@code fallback} when it fails or yields
     * {@code null}. Failures are logged as {@code "Failed to <action>: <message>"}
     * with the stack trace; {@code action} is an SLF4J pattern over {@code args}.
     */
    private <T> T call(Supplier<T> request, T fallback, String action, Object... args) {
        try {
            T result = request.get();
            return result != null ? result : fallback;
        } catch (Exception e) {
            Object[] logArgs = Arrays.copyOf(args, args.length + 2);
            logArgs[args.length] = e.getMessage();
            logArgs[args.length + 1] = e;
            log.error("Failed to " + action + ": {}", logArgs);
            return fallback;
        }
    }

    /** {@link #call} for requests without a result. */
    private void run(Runnable request, String action, Object... args) {
        call(() -> {
            request.run();
            return Boolean.TRUE;
        }, Boolean.FALSE, action, args);
    }

    /**
     * Resolves the authenticated bot's identity GUID, required to cast a vote, from
     * {@code _apis/connectionData}. Returns {@code null} when the token has no
     * resolvable identity in this organization, in which case the vote is skipped — the
     * review comment has already been posted by then, so only the vote is lost.
     * <p>
     * {@code connectionData} is the only lookup: Azure DevOps Personal Access Tokens
     * authenticate as HTTP Basic with an <em>empty</em> username, so the credentials carry
     * no identity to fall back on.
     */
    private String resolveReviewerId() {
        String cached = cachedReviewerId;
        if (cached != null) {
            return cached;
        }
        try {
            GitUser identity = getAuthenticatedUser();
            if (identity != null && identity.id() != null && !identity.id().isBlank()) {
                cachedReviewerId = identity.id();
                return cachedReviewerId;
            }
        } catch (Exception e) {
            // A timeout or a 503 says nothing about whether this token has an identity
            // here, so it is left uncached, and the next vote looks again.
            log.debug("connectionData lookup failed; skipping vote: {}", e.getMessage());
            return null;
        }
        return null;
    }

    /**
     * Resolves the identity from {@code connectionData}. Its {@code id} is the same at account
     * and organization level and is what webhook payloads and {@code @<id>} mentions identify the
     * bot by. The username is the {@code Account} property, which on Azure DevOps Server lacks
     * the domain of the {@code uniqueName}, so it is only informational. It is a UPN or an account
     * name rather than a mailbox, so no e-mail address is reported.
     * <p>
     * {@code connectionData} only answers inside a scope the token may access. An integration on
     * bare {@code https://dev.azure.com} serves any organization, so it asks the account-level
     * host, which only accepts tokens scoped to all accessible organizations. Every other
     * integration URL already names its organization or server, so it asks that.
     */
    @Override
    public GitUser getAuthenticatedUser() {
        boolean accountLevel = servesAnyOrganization(credentials.baseUrl());
        RestClient.RequestHeadersSpec<?> request = accountLevel
                ? restClient.get().uri(URI.create(
                        ACCOUNT_CONNECTION_DATA_URL + "?api-version=" + API_VERSION_CONNECTION_DATA))
                : restClient.get().uri(builder -> builder
                        .path("/_apis/connectionData")
                        .queryParam("api-version", API_VERSION_CONNECTION_DATA)
                        .build());
        ConnectionData data;
        try {
            data = request.retrieve().body(ConnectionData.class);
        } catch (RestClientException e) {
            if (accountLevel) {
                throw new IllegalStateException("Azure DevOps did not identify the token on "
                        + "https://dev.azure.com: use a token scoped to all accessible organizations, "
                        + "or add the organization to the integration URL (" + e.getMessage() + ")", e);
            }
            throw e;
        }
        ConnectionData.Identity identity = data == null ? null : data.getAuthenticatedUser();
        if (identity != null && identity.getId() != null && !identity.getId().isBlank()
                && identity.getDisplayName() != null) {
            return new GitUser(identity.getId(), identity.getDisplayName(), identity.getProviderDisplayName(), null);
        }
        throw new IllegalStateException("Azure DevOps returned no authenticated identity");
    }

    /**
     * Azure DevOps mentions are picked via the {@code @} autocomplete, which searches by display
     * name, so that name is what users should type.
     */
    @Override
    public String getBotMentionName(GitUser gitUser) {
        return gitUser.displayName() != null ? gitUser.displayName() : gitUser.username();
    }

    /** Whether {@code url} is bare {@code https://dev.azure.com}, which names no organization. */
    private static boolean servesAnyOrganization(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String path = uri.getPath();
        return "dev.azure.com".equalsIgnoreCase(uri.getHost()) && (path == null || path.isBlank() || "/".equals(path));
    }

    record DiffRange(String baseSha, String headSha) {

    }
}
