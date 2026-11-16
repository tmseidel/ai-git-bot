package org.remus.giteabot.azuredevops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.azuredevops.model.ThreadCommentId;
import org.remus.giteabot.event.BotAccountResolver;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates Azure DevOps Service Hook events into provider-neutral {@link WebhookEvent}s.
 * <p>
 * Unlike GitHub, GitLab and Bitbucket, Azure DevOps does not send its event type in
 * an HTTP header — it is carried in the body as {@code eventType}.
 * <p>
 * Azure DevOps event names are notoriously misleading:
 * <ul>
 *   <li>{@code git.pullrequest.created} — PR opened.</li>
 *   <li>{@code git.pullrequest.updated} — fires on status changes, reviewer
 *       additions and vote changes, and pushes to the source branch.
 *       {@code resource.status} of {@code completed} or {@code abandoned} is the
 *       <em>only</em> close signal. Everything else — a push, a vote, an edit, the bot
 *       being added as a reviewer, an abandoned pull request going active again — is
 *       told apart by comparing the event with the previous one
 *       ({@link PullRequestStateTracker}), see {@link #classifyUpdate}.</li>
 *   <li>{@code git.pullrequest.merged} — titled "Pull request merge attempted" by
 *       Azure DevOps. It fires whenever the service builds the *preview* merge
 *       commit, including at PR creation and on every subsequent push. It carries
 *       no lifecycle information beyond what {@code updated} already provides and
 *       must never be treated as a close — doing so would tear down the bot's
 *       session moments after every PR is opened. It is ignored.</li>
 *   <li>{@code ms.vss-code.git-pullrequest-comment-event} — comment added.</li>
 * </ul>
 * Azure DevOps sends no actor on pull request events — only the pull request's
 * author — so {@link BotIdentity#triggered(WebhookPayload)} can only
 * recognise self-triggered events for comments (which do carry their author) and
 * for {@code git.pullrequest.created}. The bot's own reviewer vote arrives as a
 * {@code git.pullrequest.updated} event and is filtered out because it leaves the
 * source branch where it was.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureDevopsWebhookTranslator implements WebhookTranslator {

    private static final String REFS_HEADS_PREFIX = "refs/heads/";

    /** Matches the thread id in a comment's {@code _links} hrefs. */
    private static final Pattern THREAD_ID = Pattern.compile("/threads/(\\d+)");

    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final PullRequestStateTracker stateTracker;
    private final BotAccountResolver botAccountResolver;

    @Override
    public RepositoryType providerType() {
        return RepositoryType.AZURE_DEVOPS;
    }

    /**
     * Azure DevOps Service Hooks send no signature over the body. The subscription's "HTTP
     * headers" field carries the shared secret instead: {@code X-AiGitBot-Token: <secret>}.
     */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches(signingSecret, headers.get("X-AiGitBot-Token"));
    }

    @Override
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventType = (String) payload.get("eventType");
        if (eventType == null) {
            log.warn("Missing eventType in Azure DevOps webhook payload");
            return WebhookEvent.ignored("missing eventType in Azure DevOps payload");
        }

        log.debug("Processing Azure DevOps event: {} for bot '{}'", eventType, bot.getName());

        // translatePayload returns null for every event type this translator does not act
        // on — git.pullrequest.merged ("Pull request merge attempted") above all — so the
        // switch below only ever sees the three it handles.
        WebhookPayload webhookPayload = translatePayload(eventType, payload);
        if (webhookPayload == null) {
            return WebhookEvent.ignored("unhandled Azure DevOps event type '" + eventType + "'");
        }

        // Azure DevOps answers the identity lookup only inside an organization the token may
        // access, so it is asked in the repository's owner, the event's organization.
        RepositoryUser botAccount = botAccountResolver.resolve(bot,
                webhookPayload.getRepository().getOwner().getLogin());
        if (botAccount == null) {
            return WebhookEvent.ignored("bot account could not be resolved");
        }
        BotIdentity identity = new BotIdentity(botAccount);
        return switch (eventType) {
            case "git.pullrequest.created" -> {
                // Seed the head sha and the reviewer list so the git.pullrequest.updated
                // event Azure DevOps fires moments after creation is mistaken neither for
                // a push nor for a fresh review request.
                stateTracker.headMoved(bot, webhookPayload);
                botReviewerAdded(bot, identity, webhookPayload);
                yield WebhookEvent.of(WebhookEventKind.PR_OPENED, webhookPayload, identity);
            }
            case "git.pullrequest.updated" -> classifyUpdate(bot, identity, webhookPayload);
            case "ms.vss-code.git-pullrequest-comment-event" -> classifyComment(bot, identity, webhookPayload);
            // Required for exhaustiveness only: anything else already left via the null
            // payload above.
            default -> WebhookEvent.ignored("unhandled Azure DevOps event type '" + eventType + "'");
        };
    }

    // ---- Classification ----

    /**
     * {@code git.pullrequest.updated} is not a push notification: it also fires for votes,
     * title and description edits, merge status changes, reviewer changes and
     * reactivations. Each is recognised by comparing with the previous event, so that
     * {@code runOnPrUpdate} reacts to pushes only, like GitHub's {@code synchronize}.
     */
    private WebhookEvent classifyUpdate(Bot bot, BotIdentity identity, WebhookPayload payload) {
        // Completion and abandonment arrive here, not on git.pullrequest.merged.
        if (isClosed(payload)) {
            stateTracker.markClosed(bot, payload);
            return WebhookEvent.of(WebhookEventKind.PR_CLOSED, payload, identity);
        }
        // All three record what they saw, so none may be short-circuited away.
        boolean reopened = stateTracker.consumeReopened(bot, payload);
        boolean moved = stateTracker.headMoved(bot, payload);
        boolean reviewerAdded = botReviewerAdded(bot, identity, payload);
        if (reopened) {
            // Azure DevOps has no reopened event: reactivation is an update whose status
            // is active again. Classified as a reopen so it gets the treatment the other
            // providers give one — reviewed when the bot is a requested reviewer, not only
            // when run-on-update is on.
            return WebhookEvent.of(WebhookEventKind.PR_REOPENED, payload, identity);
        }
        if (reviewerAdded) {
            // The Azure DevOps equivalent of a review_requested event elsewhere.
            return WebhookEvent.of(WebhookEventKind.PR_REVIEWER_ADDED, payload, identity);
        }
        if (!moved) {
            return WebhookEvent.ignored("source branch of PR #" + payload.getNumber()
                    + " unchanged (vote, title or description edit)");
        }
        return WebhookEvent.of(WebhookEventKind.PR_SYNCHRONIZED, payload, identity);
    }

    private WebhookEvent classifyComment(Bot bot, BotIdentity identity, WebhookPayload payload) {
        // Only once the comment is known to address the bot — and not to come from it —
        // is it worth spending an API call to find out whether it is anchored to a file.
        // Azure DevOps fires this event for every comment on every pull request.
        if (identity.isMentionedIn(payload) && !identity.triggered(payload)) {
            hydrateInlineThreadContext(bot, payload);
        }
        // Inline (per-file) comments carry a thread context path.
        boolean inline = payload.getComment() != null && payload.getComment().getPath() != null;
        return WebhookEvent.of(inline ? WebhookEventKind.PR_INLINE_COMMENT : WebhookEventKind.PR_COMMENT, payload, identity);
    }

    // ---- Reviewer tracking ----

    /**
     * Reports whether the bot has just been added to the reviewer list — Azure DevOps has
     * no {@code review_requested} event — and records the list's new state.
     * <p>
     * A payload without a reviewer list leaves the recorded state untouched: subscriptions
     * with reduced "Resource details to send" omit it, and reading the omission as a
     * removal would make the next full payload look like a fresh request.
     * <p>
     * A bot that already carries a vote is not counted as newly requested: casting a vote
     * adds the caller to the reviewer list, so the bot's own post-review vote would
     * otherwise trigger a second review.
     */
    private boolean botReviewerAdded(Bot bot, BotIdentity identity, WebhookPayload payload) {
        if (payload.getPullRequest() == null
                || payload.getPullRequest().getRequestedReviewers() == null) {
            return false;
        }
        boolean added = stateTracker.reviewerAdded(bot, payload, identity.isReviewer(payload));
        return added && !botHasVoted(identity, payload);
    }

    private static boolean botHasVoted(BotIdentity identity, WebhookPayload payload) {
        return payload.getPullRequest().getRequestedReviewers().stream()
                .anyMatch(reviewer -> identity.is(reviewer)
                        && reviewer.getVote() != null && reviewer.getVote() != 0);
    }

    // ---- Inline comment hydration ----

    /**
     * Fills in the file path and line of an inline comment.
     * <p>
     * The {@code ms.vss-code.git-pullrequest-comment-event} payload carries the comment
     * but never the enclosing thread's {@code threadContext}, so the anchor has to be
     * fetched, using the thread id {@link #extractComment} read from the comment's
     * {@code _links}.
     * <p>
     * Every failure path leaves the comment untouched, so an unresolvable anchor degrades
     * to handling the comment as a top-level one rather than dropping it.
     */
    private void hydrateInlineThreadContext(Bot bot, WebhookPayload payload) {
        if (payload.getComment() == null || payload.getPullRequest() == null
                || payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long threadId = payload.getComment().getId() instanceof ThreadCommentId threadCommentId ? threadCommentId.getThreadId() : null;
        if (threadId == null) {
            return;
        }
        try {
            RepositoryApiClient client = repositoryPlatformFactory.getApiClient(bot.getGitIntegration());
            if (!(client instanceof AzureDevopsApiClient azureClient)) {
                return;
            }
            AzureDevopsApiClient.InlineThreadContext context = azureClient.getInlineThreadContext(
                    payload.getRepository().getOwner().getLogin(),
                    payload.getRepository().getName(),
                    payload.getPullRequest().getNumber(),
                    threadId);
            if (context != null) {
                payload.getComment().setPath(context.path());
                payload.getComment().setLine(context.line());
            }
        } catch (Exception e) {
            log.warn("Could not resolve the thread context of comment #{} on PR #{}: {}",
                    payload.getComment().getId(), payload.getNumber(), e.getMessage());
        }
    }

    /**
     * The comment event payload names no thread id, so only {@code _links} betrays which
     * thread the comment belongs to: {@code _links.threads.href} ends in
     * {@code /threads/{id}} and {@code _links.self.href} in
     * {@code /threads/{id}/comments/{commentId}}.
     *
     * @see <a href="https://learn.microsoft.com/en-us/azure/devops/service-hooks/events?view=azure-devops#pull-request-commented-on">Pull request commented on</a>
     */
    Long threadIdFromComment(Map<String, Object> comment) {
        if (!(comment.get("_links") instanceof Map<?, ?> links)) {
            return null;
        }
        Long fromThreads = threadIdFromHref(links.get("threads"));
        return fromThreads != null ? fromThreads : threadIdFromHref(links.get("self"));
    }

    private static Long threadIdFromHref(Object link) {
        if (!(link instanceof Map<?, ?> map) || !(map.get("href") instanceof String href)) {
            return null;
        }
        Matcher matcher = THREAD_ID.matcher(href);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Azure DevOps → WebhookPayload translation ----

    WebhookPayload translatePayload(String eventType, Map<String, Object> raw) {
        return switch (eventType) {
            case "git.pullrequest.created" -> translatePullRequestEvent(raw, true);
            case "git.pullrequest.updated" -> translatePullRequestEvent(raw, false);
            case "ms.vss-code.git-pullrequest-comment-event" -> translateCommentEvent(raw);
            // git.pullrequest.merged ("Pull request merge attempted") and any other/unknown
            // event type carry no actionable lifecycle information — ignored.
            default -> null;
        };
    }

    /** Whether the pull request was completed (merged) or abandoned. */
    private static boolean isClosed(WebhookPayload payload) {
        String status = payload.getPullRequest() != null ? payload.getPullRequest().getState() : null;
        return "completed".equals(status) || "abandoned".equals(status);
    }

    /**
     * @param created whether the event is {@code git.pullrequest.created}; only then is the
     *                pull request's author also the event's sender
     */
    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw, boolean created) {
        Map<String, Object> resource = (Map<String, Object>) raw.get("resource");
        if (resource == null) {
            return null;
        }

        WebhookPayload.Repository repository =
                extractRepository((Map<String, Object>) resource.get("repository"), raw);
        if (repository == null) {
            return null;
        }

        WebhookPayload payload = new WebhookPayload();
        // resource.createdBy is the pull request's *author*, not the user whose action
        // raised the event — Azure DevOps sends no actor on pull request events at all.
        // The two coincide only on creation, so the sender is set only there.
        //
        // Carrying the author over to updates (pushes, closes) would make
        // BotIdentity#triggered treat every event on a bot-created pull request
        // (the offer-as-pr lifecycle, createPullRequest) as self-triggered and drop it,
        // so a human pushing to the bot's own PR would never get a re-review. Leaving it
        // unset costs nothing: self-triggered updates — notably the bot's own reviewer
        // vote — are filtered by the head-sha comparison in #classifyUpdate.
        if (created) {
            payload.setSender(extractUser((Map<String, Object>) resource.get("createdBy")));
        }
        payload.setRepository(repository);
        payload.setPullRequest(extractPullRequest(resource));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translateCommentEvent(Map<String, Object> raw) {
        Map<String, Object> resource = (Map<String, Object>) raw.get("resource");
        if (resource == null) {
            return null;
        }
        Map<String, Object> pr = (Map<String, Object>) resource.get("pullRequest");
        if (pr == null) {
            // A malformed comment payload without resource.pullRequest carries no
            // pull request, repository, number or issue. Ignoring it here (rather
            // than returning a payload with those fields null) keeps the comment
            // handlers from ever being reached with a null pull request when the
            // comment body happens to mention the bot alias.
            return null;
        }
        Map<String, Object> comment = (Map<String, Object>) resource.get("comment");

        WebhookPayload.Repository repository =
                extractRepository((Map<String, Object>) pr.get("repository"), raw);
        if (repository == null) {
            return null;
        }

        WebhookPayload payload = new WebhookPayload();
        payload.setSender(extractUser(comment != null ? (Map<String, Object>) comment.get("author") : null));
        payload.setComment(extractComment(comment));

        payload.setRepository(repository);
        payload.setPullRequest(extractPullRequest(pr));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    // ---- Extraction helpers ----

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest extractPullRequest(Map<String, Object> resource) {
        if (resource == null) {
            return null;
        }
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        Long id = toLong(resource.get("pullRequestId"));
        pullRequest.setId(id);
        pullRequest.setNumber(id);
        pullRequest.setTitle((String) resource.get("title"));
        pullRequest.setBody((String) resource.get("description"));
        String status = (String) resource.get("status");
        pullRequest.setState(status);
        pullRequest.setMerged("completed".equals(status));
        pullRequest.setUser(extractUser((Map<String, Object>) resource.get("createdBy")));
        pullRequest.setRequestedReviewers(
                extractReviewers((List<Map<String, Object>>) resource.get("reviewers")));

        pullRequest.setHead(extractRef((String) resource.get("sourceRefName"),
                (Map<String, Object>) resource.get("lastMergeSourceCommit")));
        pullRequest.setBase(extractRef((String) resource.get("targetRefName"),
                (Map<String, Object>) resource.get("lastMergeTargetCommit")));

        return pullRequest;
    }

    private WebhookPayload.Head extractRef(String refName, Map<String, Object> commit) {
        if (refName == null && commit == null) {
            return null;
        }
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef(stripRefsHeadsPrefix(refName));
        if (commit != null) {
            head.setSha((String) commit.get("commitId"));
        }
        return head;
    }

    private String stripRefsHeadsPrefix(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.startsWith(REFS_HEADS_PREFIX) ? ref.substring(REFS_HEADS_PREFIX.length()) : ref;
    }

    /**
     * Builds the {@link WebhookPayload.Repository} that every consumer downstream reads
     * {@code getName()} from to construct the {@code repo} argument passed into
     * {@link org.remus.giteabot.repository.RepositoryApiClient}. Azure DevOps addresses
     * repositories as {@code owner} = organization and {@code repo} =
     * {@code "Project/Repository"}, so {@code name} must carry the compound form.
     * <p>
     * The compound form belongs in {@code name}, not only in {@code fullName}: consumers
     * ({@code BotWebhookService}, {@code CodeReviewService}, {@code AgentReviewService},
     * {@code IssueImplementationService} and others) read {@code getName()} for the
     * {@code repo} argument, while {@code getFullName()} reaches only log messages. With
     * the bare repository name in {@code name}, {@link AzureDevopsAddress#parse} throws on
     * every webhook-triggered call; {@code AzureDevopsPayloadTranslationTest} covers
     * that boundary.
     * <p>
     * The organization (the {@code owner} half of the pair) is resolved from the Service
     * Hook envelope's {@code resourceContainers}, never from the repository's own
     * {@code url} and never from the integration's configured base URL — see
     * {@link #organizationFromResourceContainers} for why.
     *
     * @return {@code null} when the project name or the organization cannot be
     *         determined, so the caller ignores the event rather than building a
     *         repository identifier that would fail deep inside the client.
     */
    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository extractRepository(Map<String, Object> repo,
                                                        Map<String, Object> raw) {
        if (repo == null) {
            return null;
        }
        String name = (String) repo.get("name");
        Map<String, Object> project = (Map<String, Object>) repo.get("project");
        String projectName = project != null ? (String) project.get("name") : null;
        if (name == null || projectName == null) {
            log.error("Azure DevOps webhook repository payload is missing name or project "
                    + "(name='{}', project='{}'); ignoring event", name, projectName);
            return null;
        }

        String organization = organizationFromResourceContainers(raw);
        if (organization == null) {
            log.error("Could not resolve the Azure DevOps organization for repository "
                    + "'{}/{}'; ignoring event", projectName, name);
            return null;
        }

        String projectAndName = projectName + "/" + name;
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(projectAndName);
        repository.setFullName(projectAndName);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin(organization);
        repository.setOwner(owner);

        return repository;
    }

    private WebhookPayload.Owner extractUser(Map<String, Object> user) {
        if (user == null) {
            return null;
        }
        // The id is the identity GUID, which the bot is recognised by. As the login only
        // uniqueName will do: displayName is user-editable, and the login feeds the whitelist
        // and PR-author checks, so falling back to it would let anyone match those by
        // renaming themselves.
        return WebhookPayload.Owner.of(user.get("id"), (String) user.get("uniqueName"));
    }

    /**
     * Maps the payload's reviewer entries to owners, dropping any that carry no identity.
     * <p>
     * {@code Stream.toList()} keeps nulls, and {@link #extractUser} returns one for an
     * entry that is absent or not an object, so an unfiltered list would hand
     * {@link BotIdentity#isReviewer} a null to call {@code getId()} on. An empty list still
     * means "no reviewers"; only a {@code null} list means "this subscription does not
     * send them", which {@link #botReviewerAdded} must keep telling apart.
     */
    private List<WebhookPayload.Owner> extractReviewers(List<Map<String, Object>> reviewers) {
        if (reviewers == null) {
            return null;
        }
        return reviewers.stream()
                .map(reviewer -> {
                    WebhookPayload.Owner owner = extractUser(reviewer);
                    if (owner != null && reviewer.get("vote") instanceof Number vote) {
                        owner.setVote(vote.intValue());
                    }
                    return owner;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) {
            return null;
        }
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        // Comment ids are only unique within their thread, so the thread id is needed to
        // address the comment again (e.g. to update it).
        Long threadId = threadIdFromComment(comment);
        Long commentId = toLong(comment.get("id"));
        if (threadId == null && commentId != null) {
            log.debug("No thread id in _links of comment #{}; the comment cannot be addressed again", commentId);
        }
        c.setId(ThreadCommentId.of(threadId, commentId));
        c.setBody((String) comment.get("content"));
        c.setUser(extractUser((Map<String, Object>) comment.get("author")));

        // path/line are deliberately left unset here. Azure DevOps hangs threadContext off
        // the *thread*, not the comment, and the comment event payload contains only the
        // comment (id, author, content, commentType, _links). They are filled in later by
        // hydrateInlineThreadContext, which needs an API call to reach the thread.

        return c;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * Resolves the Azure DevOps organization — the project collection on Azure DevOps
     * Server — from the Service Hook envelope's
     * {@code resourceContainers.collection.baseUrl}, never from {@code GitIntegration.url}
     * and never from a resource's own {@code url}.
     * <p>
     * {@code GitIntegration.url} is the instance root and carries no organization — one
     * integration serves many organizations — while
     * {@code AzureDevopsProviderMetadata.resolveApiUrl} uses that same root verbatim as
     * the REST base URL, to which every request appends {@code /{org}/{project}/_apis/...}.
     * Reading the organization from the integration URL would therefore either mis-resolve
     * it or double it into every request path.
     * <p>
     * It is not read from {@code resource.repository.url} either. That url is
     * <em>project</em>-scoped, so the segment preceding {@code _apis} is the project id and
     * not the collection:
     * <pre>
     * https://tfs.example.com/tfs/Experimental/ce0eacb6-.../_apis/git/repositories/...
     *                             ^collection  ^project id
     * </pre>
     * Locating the collection relative to {@code _apis} therefore yields the project id,
     * and the resulting request fails as a 401 rather than a 404 — Azure DevOps Server
     * resolves the collection before it authorizes, so a collection that does not exist is
     * never reached by a token scoped to the real one.
     * <p>
     * {@code resourceContainers.collection.baseUrl} carries no such ambiguity: it ends at
     * the collection by definition, whatever the deployment.
     * <pre>
     * https://dev.azure.com/fabrikam/           -&gt; "fabrikam"      (last path segment)
     * https://fabrikam.visualstudio.com/        -&gt; "fabrikam"      (host label, legacy)
     * https://tfs.example.com/tfs/Experimental/ -&gt; "Experimental"  (collection, Server)
     * </pre>
     * The last path segment covers both the modern and the Server form, including a
     * collection sitting behind any number of virtual-directory segments. The legacy host
     * form is checked first, because its base url may carry a collection segment that is
     * not the organization.
     * <p>
     * Whether the resolved organization is then repeated in outbound request paths is
     * decided separately, by {@link AzureDevopsAddress#isOrganizationScopedBy} — for the
     * legacy form, and for a Server instance configured with its collection, the base URL
     * already carries it.
     *
     * @return the organization, or {@code null} when the envelope carries no collection
     *         container, its base url is unparseable, or it is indistinguishable from the
     *         deployment root — the caller must then ignore the event rather than guess,
     *         so a wrong organization never silently surfaces as an opaque 401 later.
     */
    @SuppressWarnings("unchecked")
    String organizationFromResourceContainers(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> containers = (Map<String, Object>) raw.get("resourceContainers");
        if (containers == null) {
            log.error("Azure DevOps webhook carries no resourceContainers; "
                    + "cannot resolve the organization");
            return null;
        }
        String collectionBaseUrl = containerBaseUrl(containers, "collection");
        if (collectionBaseUrl == null) {
            log.error("Azure DevOps webhook carries no usable "
                    + "resourceContainers.collection.baseUrl; cannot resolve the organization");
            return null;
        }
        // Azure DevOps Server reports the deployment root separately. A collection base url
        // equal to it names no collection, and the last-segment rule would then return the
        // virtual directory ("tfs") as the organization.
        String serverBaseUrl = containerBaseUrl(containers, "server");
        if (serverBaseUrl != null && withoutTrailingSlashes(collectionBaseUrl)
                .equalsIgnoreCase(withoutTrailingSlashes(serverBaseUrl))) {
            log.error("Azure DevOps webhook resourceContainers.collection.baseUrl '{}' is the "
                    + "deployment root and names no collection", collectionBaseUrl);
            return null;
        }
        return organizationFromCollectionBaseUrl(collectionBaseUrl);
    }

    /**
     * The {@code baseUrl} of one {@code resourceContainers} entry, or {@code null} when the
     * entry is absent or carries no usable url.
     */
    private static String containerBaseUrl(Map<String, Object> containers, String key) {
        if (!(containers.get(key) instanceof Map<?, ?> container)) {
            return null;
        }
        return container.get("baseUrl") instanceof String baseUrl && !baseUrl.isBlank()
                ? baseUrl
                : null;
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * The organization named by a {@code resourceContainers.collection.baseUrl}: the
     * leading host label for the legacy {@code *.visualstudio.com} form, otherwise the last
     * path segment. See {@link #organizationFromResourceContainers} for why this url is the
     * one read.
     */
    String organizationFromCollectionBaseUrl(String collectionBaseUrl) {
        if (collectionBaseUrl == null || collectionBaseUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(collectionBaseUrl);
        } catch (IllegalArgumentException e) {
            log.error("Could not parse Azure DevOps collection base url '{}': {}",
                    collectionBaseUrl, e.getMessage());
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            log.error("Azure DevOps collection base url '{}' has no host", collectionBaseUrl);
            return null;
        }
        // Checked before the path: a legacy base url may also carry a collection segment,
        // and resolving that instead would address the wrong organization.
        if (host.toLowerCase(Locale.ROOT).endsWith(".visualstudio.com")) {
            int firstDot = host.indexOf('.');
            return firstDot > 0 ? host.substring(0, firstDot) : null;
        }
        String path = uri.getPath();
        if (path != null) {
            String lastSegment = null;
            for (String segment : path.split("/")) {
                if (!segment.isBlank()) {
                    lastSegment = segment;
                }
            }
            if (lastSegment != null) {
                return lastSegment;
            }
        }
        log.error("Could not locate an Azure DevOps organization in collection base url '{}': "
                + "no path segment and no legacy host label", collectionBaseUrl);
        return null;
    }
}
