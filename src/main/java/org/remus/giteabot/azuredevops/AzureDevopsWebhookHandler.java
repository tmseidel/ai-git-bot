package org.remus.giteabot.azuredevops;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handler for Azure DevOps Service Hook events.
 * <p>
 * Receives Azure DevOps Service Hook payloads and translates them into the common
 * {@link WebhookPayload} model used by the rest of the application, then delegates
 * to {@link BotWebhookService} for actual processing.
 * <p>
 * Unlike GitHub, GitLab and Bitbucket, Azure DevOps does not send its event type in
 * an HTTP header — it is carried in the body as {@code eventType}, so this handler
 * (like {@code GiteaWebhookHandler}) takes no header argument.
 * <p>
 * Azure DevOps event names are notoriously misleading:
 * <ul>
 *   <li>{@code git.pullrequest.created} — PR opened.</li>
 *   <li>{@code git.pullrequest.updated} — fires on status changes, reviewer
 *       additions and vote changes, and pushes to the source branch.
 *       {@code resource.status} of {@code completed} or {@code abandoned} is the
 *       <em>only</em> close signal; any other status maps to {@code synchronized},
 *       but only when the source branch actually moved — see
 *       {@link #sourceBranchMoved}. This event also stands in for two the other
 *       providers send separately, because Azure DevOps has neither: the bot being
 *       added as a reviewer ({@code review_requested}, see {@link #botReviewerAdded})
 *       and an abandoned pull request going active again ({@code reopened}, see
 *       {@link #consumeReopened}).</li>
 *   <li>{@code git.pullrequest.merged} — titled "Pull request merge attempted" by
 *       Azure DevOps. It fires whenever the service builds the *preview* merge
 *       commit, including at PR creation and on every subsequent push. It carries
 *       no lifecycle information beyond what {@code updated} already provides and
 *       must never be treated as a close — doing so would tear down the bot's
 *       session moments after every PR is opened. It is ignored.</li>
 *   <li>{@code ms.vss-code.git-pullrequest-comment-event} — comment added.</li>
 * </ul>
 * Azure DevOps sends no actor on pull request events — only the pull request's
 * author — so {@link BotWebhookService#isBotUser(Bot, WebhookPayload)} can only
 * suppress self-triggered events for comments (which do carry their author) and
 * for {@code git.pullrequest.created}. The bot's own reviewer vote arrives as a
 * {@code git.pullrequest.updated} event and is filtered by {@link #sourceBranchMoved}
 * instead, on the grounds that a vote leaves the source branch where it was.
 */
@Slf4j
@Component
public class AzureDevopsWebhookHandler {

    private static final String REFS_HEADS_PREFIX = "refs/heads/";

    /** Matches the thread id in a comment's {@code _links} hrefs. */
    private static final Pattern THREAD_ID = Pattern.compile("/threads/(\\d+)");

    /** Upper bound on tracked pull requests; the least recently touched entry is dropped. */
    private static final int MAX_TRACKED_PULL_REQUESTS = 5000;

    private final BotWebhookService botWebhookService;
    private final GiteaClientFactory clientFactory;

    /**
     * What the previous event said about one pull request: the source-branch commit it
     * carried, whether the bot was among its reviewers, and whether it left the pull
     * request closed.
     */
    private record Tracked(String headSha, boolean botIsReviewer, boolean closed) {

        /** The state of a pull request no event has been seen for yet. */
        static final Tracked NOTHING = new Tracked(null, false, false);
    }

    /**
     * Per-pull-request state, keyed by bot + repository + PR number.
     * <p>
     * Azure DevOps events carry no change set: a push, a vote, a title edit, a reviewer
     * being added and a reactivation all arrive as {@code git.pullrequest.updated} and
     * look alike. Every distinction this handler draws therefore comes from comparing an
     * event against what the one before it said — {@link #sourceBranchMoved},
     * {@link #botReviewerAdded} and {@link #consumeReopened} each own one field.
     * <p>
     * Access-ordered and capped, so a long-lived instance cannot grow without bound. The
     * state is in-memory only: after a restart the first event per pull request is taken
     * at face value, which costs at most one extra review and never drops a genuine one.
     * The same applies per node when the bot runs more than one instance behind a load
     * balancer — each keeps its own view, so a pull request whose events are spread
     * across nodes can be reviewed once per node that sees a first event for it.
     * Compound read-modify-write access synchronizes on the map, as
     * {@link Collections#synchronizedMap} requires — and as reads must anyway, since
     * access ordering makes {@code get} a structural change.
     */
    private final Map<String, Tracked> trackedPullRequests = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
                    return size() > MAX_TRACKED_PULL_REQUESTS;
                }
            });

    public AzureDevopsWebhookHandler(BotWebhookService botWebhookService,
                                     GiteaClientFactory clientFactory) {
        this.botWebhookService = botWebhookService;
        this.clientFactory = clientFactory;
    }

    /**
     * Handles an Azure DevOps Service Hook event for the given bot.
     *
     * @param bot     the bot to process the webhook for
     * @param payload the raw webhook payload, carrying the event type in {@code eventType}
     * @return response indicating the result of webhook processing
     */
    public ResponseEntity<String> handleWebhook(Bot bot, Map<String, Object> payload) {
        String eventType = (String) payload.get("eventType");
        if (eventType == null) {
            log.warn("Missing eventType in Azure DevOps webhook payload");
            return ResponseEntity.ok("ignored");
        }

        log.debug("Processing Azure DevOps event: {} for bot '{}'", eventType, bot.getName());

        // translatePayload returns null for every event type this handler does not act
        // on — git.pullrequest.merged ("Pull request merge attempted") above all — so the
        // switch below only ever sees the three it handles.
        WebhookPayload webhookPayload = translatePayload(eventType, payload);
        if (webhookPayload == null) {
            log.debug("Ignoring Azure DevOps event type: {}", eventType);
            return ResponseEntity.ok("ignored");
        }

        // Ignore events triggered by the bot itself (e.g. the bot casting its own
        // reviewer vote produces an inbound git.pullrequest.updated event).
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            String senderLogin = webhookPayload.getSender() != null ? webhookPayload.getSender().getLogin() : "null";
            log.info("Ignoring Azure DevOps webhook event from bot's own user. Bot username='{}', sender='{}'",
                    bot.getUsername(), senderLogin);
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);
        log.debug("Event passed all checks, processing {} with botAlias='{}'", eventType, botAlias);

        return switch (eventType) {
            case "git.pullrequest.created" -> {
                // Seed the head sha and the reviewer list so the git.pullrequest.updated
                // event Azure DevOps fires moments after creation is mistaken neither for
                // a push nor for a fresh review request.
                sourceBranchMoved(bot, webhookPayload);
                botReviewerAdded(bot, webhookPayload);
                yield handlePullRequestOpenedOrUpdated(bot, eventType, webhookPayload);
            }
            case "git.pullrequest.updated" -> {
                // Completion and abandonment arrive here, not on git.pullrequest.merged.
                if ("closed".equals(webhookPayload.getAction())) {
                    markClosed(bot, webhookPayload);
                    yield handlePullRequestClosed(bot, webhookPayload);
                }
                // All three record what they saw, so none may be short-circuited away.
                boolean reopened = consumeReopened(bot, webhookPayload);
                boolean moved = sourceBranchMoved(bot, webhookPayload);
                boolean reviewerAdded = botReviewerAdded(bot, webhookPayload);
                if (reopened) {
                    // Azure DevOps has no reopened event: reactivation is an update whose
                    // status is active again. Relabelled so the dispatch below gives it
                    // the treatment the other providers give "reopened" — reviewed when
                    // the bot is a requested reviewer, not only when run-on-update is on.
                    webhookPayload.setAction("reopened");
                    yield handlePullRequestOpenedOrUpdated(bot, eventType, webhookPayload);
                }
                if (reviewerAdded) {
                    // The Azure DevOps equivalent of the review_requested arm in the
                    // Gitea and GitHub handlers: an explicit review request triggers a
                    // review on its own, independently of the run-on-create and
                    // run-on-update switches.
                    log.debug("Bot was added as a reviewer of PR #{}, triggering a review",
                            webhookPayload.getNumber());
                    botWebhookService.reviewPullRequest(bot, webhookPayload);
                    yield ResponseEntity.ok("review triggered");
                }
                if (!moved) {
                    log.debug("Ignoring git.pullrequest.updated for PR #{}: source branch "
                                    + "unchanged (vote, title or description edit)",
                            webhookPayload.getNumber());
                    yield ResponseEntity.ok("ignored");
                }
                yield handlePullRequestOpenedOrUpdated(bot, eventType, webhookPayload);
            }
            case "ms.vss-code.git-pullrequest-comment-event" ->
                    handlePullRequestComment(bot, payload, webhookPayload, botAlias);
            // Required for exhaustiveness only: anything else already left via the null
            // payload above.
            default -> ResponseEntity.ok("ignored");
        };
    }

    private ResponseEntity<String> handlePullRequestOpenedOrUpdated(Bot bot, String eventType, WebhookPayload payload) {
        String action = payload.getAction();
        if (("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
                || ("reopened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
                || ("synchronized".equals(action) && bot.isRunOnPrUpdate())) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }
        return ResponseEntity.ok("ignored");
    }

    private ResponseEntity<String> handlePullRequestClosed(Bot bot, WebhookPayload payload) {
        botWebhookService.handlePrClosed(bot, payload);
        return ResponseEntity.ok("session closed");
    }

    private ResponseEntity<String> handlePullRequestComment(Bot bot, Map<String, Object> raw,
                                                            WebhookPayload payload, String botAlias) {
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (body == null || !body.contains(botAlias)) {
            return ResponseEntity.ok("ignored");
        }

        // Only now — once the comment is known to address the bot — is it worth spending
        // an API call to find out whether it is anchored to a file. Azure DevOps fires
        // this event for every comment on every pull request.
        hydrateInlineThreadContext(bot, raw, payload);

        // Inline (per-file) comments carry a thread context path.
        if (payload.getComment().getPath() != null) {
            botWebhookService.handleInlineComment(bot, payload);
            return ResponseEntity.ok("inline comment response triggered");
        }

        if (botWebhookService.isReviewAgainRequest(payload, botAlias)) {
            if (botWebhookService.isReviewAgainRequestFromPullRequestAuthor(payload, botAlias)) {
                botWebhookService.reviewPullRequest(bot, payload);
                return ResponseEntity.ok("review triggered");
            }
            return ResponseEntity.ok("ignored");
        }

        // General PR comment mentioning the bot
        botWebhookService.handleBotCommand(bot, payload);
        return ResponseEntity.ok("command received");
    }

    // ---- Event-to-event state tracking ----

    /**
     * Reports whether the pull request's source branch has moved since the last event,
     * and records the new position.
     * <p>
     * {@code git.pullrequest.updated} is not a push notification: Azure DevOps also fires
     * it when a reviewer votes, when the title or description is edited, and when the
     * merge status changes. Treating all of those as {@code synchronized} makes a bot with
     * {@code runOnPrUpdate} enabled re-review the whole pull request every time a human
     * clicks Approve. GitHub's {@code synchronize} fires only on new commits, and this
     * restores that meaning by comparing {@code lastMergeSourceCommit.commitId}.
     * <p>
     * Returns {@code true} when the sha is unknown — a subscription configured with a
     * reduced "Resource details to send" omits the commit, and silently dropping those
     * events would be far worse than an occasional redundant review.
     */
    private boolean sourceBranchMoved(Bot bot, WebhookPayload payload) {
        String headSha = payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                ? payload.getPullRequest().getHead().getSha()
                : null;
        if (headSha == null || headSha.isBlank()) {
            log.debug("No source commit in Azure DevOps payload for PR #{}; "
                    + "treating the event as a source-branch update", payload.getNumber());
            return true;
        }
        String key = trackingKey(bot, payload);
        synchronized (trackedPullRequests) {
            Tracked previous = trackedPullRequests.getOrDefault(key, Tracked.NOTHING);
            trackedPullRequests.put(key,
                    new Tracked(headSha, previous.botIsReviewer(), previous.closed()));
            return !headSha.equals(previous.headSha());
        }
    }

    /**
     * Records that the pull request was completed or abandoned: the head sha and the
     * reviewer list are forgotten, so a reactivation at the same commit is not mistaken
     * for a duplicate, while the closure itself is remembered for
     * {@link #consumeReopened}.
     */
    private void markClosed(Bot bot, WebhookPayload payload) {
        trackedPullRequests.put(trackingKey(bot, payload), new Tracked(null, false, true));
    }

    /**
     * Reports whether this event reactivates a pull request that was seen closed, and
     * clears the flag so only the first event after the reactivation counts.
     * <p>
     * Azure DevOps has no {@code reopened} event either: an abandoned pull request that
     * is reactivated simply produces a {@code git.pullrequest.updated} whose status is
     * {@code active} again. Recognising it matters because every other provider treats a
     * reopened pull request like a freshly opened one — reviewed when the bot is a
     * requested reviewer, or when {@code runOnPrCreation} is set — whereas an
     * unrecognised reactivation would be a plain {@code synchronized} and so need
     * {@code runOnPrUpdate} instead.
     */
    private boolean consumeReopened(Bot bot, WebhookPayload payload) {
        String key = trackingKey(bot, payload);
        synchronized (trackedPullRequests) {
            Tracked previous = trackedPullRequests.getOrDefault(key, Tracked.NOTHING);
            if (!previous.closed()) {
                return false;
            }
            trackedPullRequests.put(key,
                    new Tracked(previous.headSha(), previous.botIsReviewer(), false));
            return true;
        }
    }

    /**
     * Reports whether the bot has just been added to the pull request's reviewer list,
     * and records the list's new state.
     * <p>
     * Azure DevOps has no {@code review_requested} event — a reviewer added to an open
     * pull request is only a {@code git.pullrequest.updated} whose payload happens to
     * carry the bot in {@code resource.reviewers}. Without this comparison the bot would
     * either never react to being requested after the pull request was opened (the
     * reviewer list is otherwise consulted only on creation, while
     * {@link #sourceBranchMoved} drops the event because the branch did not move), or,
     * if the list were checked unconditionally, re-review on every later vote and title
     * edit for as long as it stays a reviewer.
     * <p>
     * A payload without a reviewer list at all leaves the recorded state untouched: a
     * subscription configured with reduced "Resource details to send" omits
     * {@code reviewers} from every event, and treating the omission as a removal would
     * make the next full payload look like a fresh request.
     */
    private boolean botReviewerAdded(Bot bot, WebhookPayload payload) {
        if (payload.getPullRequest() == null
                || payload.getPullRequest().getRequestedReviewers() == null) {
            return false;
        }
        String key = trackingKey(bot, payload);
        boolean isReviewer = hasBotReviewer(bot, payload);
        synchronized (trackedPullRequests) {
            Tracked previous = trackedPullRequests.getOrDefault(key, Tracked.NOTHING);
            trackedPullRequests.put(key,
                    new Tracked(previous.headSha(), isReviewer, previous.closed()));
            return isReviewer && !previous.botIsReviewer();
        }
    }

    private static String trackingKey(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository() != null && payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin()
                : null;
        String repo = payload.getRepository() != null ? payload.getRepository().getName() : null;
        return bot.getId() + ":" + owner + "/" + repo + "#" + payload.getNumber();
    }

    // ---- Inline comment hydration ----

    /**
     * Fills in the file path and line of an inline comment.
     * <p>
     * The {@code ms.vss-code.git-pullrequest-comment-event} payload carries the comment
     * but never the enclosing thread's {@code threadContext}, so the anchor has to be
     * fetched. Only {@code _links} betrays which thread the comment belongs to:
     * {@code _links.threads.href} ends in {@code /threads/{id}} and
     * {@code _links.self.href} in {@code /threads/{id}/comments/{commentId}}.
     * <p>
     * Every failure path leaves the comment untouched, so an unresolvable anchor degrades
     * to handling the comment as a top-level one rather than dropping it.
     */
    private void hydrateInlineThreadContext(Bot bot, Map<String, Object> raw,
                                            WebhookPayload payload) {
        if (payload.getComment() == null || payload.getPullRequest() == null
                || payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long threadId = threadIdFromComment(raw);
        if (threadId == null) {
            return;
        }
        try {
            RepositoryApiClient client = clientFactory.getApiClient(bot.getGitIntegration());
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

    Long threadIdFromComment(Map<String, Object> raw) {
        Object resource = raw.get("resource");
        if (!(resource instanceof Map<?, ?> resourceMap)) {
            return null;
        }
        if (!(resourceMap.get("comment") instanceof Map<?, ?> comment)
                || !(comment.get("_links") instanceof Map<?, ?> links)) {
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
            case "git.pullrequest.created" -> translatePullRequestEvent(raw, "opened");
            case "git.pullrequest.updated" -> translatePullRequestUpdatedEvent(raw);
            case "ms.vss-code.git-pullrequest-comment-event" -> translateCommentEvent(raw);
            // git.pullrequest.merged ("Pull request merge attempted") and any other/unknown
            // event type carry no actionable lifecycle information — ignored.
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestUpdatedEvent(Map<String, Object> raw) {
        Map<String, Object> resource = (Map<String, Object>) raw.get("resource");
        String status = resource != null ? (String) resource.get("status") : null;
        String action = ("completed".equals(status) || "abandoned".equals(status)) ? "closed" : "synchronized";
        return translatePullRequestEvent(raw, action);
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw, String action) {
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
        payload.setAction(action);
        // resource.createdBy is the pull request's *author*, not the user whose action
        // raised the event — Azure DevOps sends no actor on pull request events at all.
        // The two coincide only on creation, so the sender is set only there.
        //
        // Carrying the author over to "synchronized" and "closed" would make
        // BotWebhookService#isBotUser treat every event on a bot-created pull request
        // (the offer-as-pr lifecycle, createPullRequest) as self-triggered and drop it,
        // so a human pushing to the bot's own PR would never get a re-review. Leaving it
        // unset costs nothing: self-triggered updates — notably the bot's own reviewer
        // vote — are filtered by #sourceBranchMoved, which is what actually catches them.
        if ("opened".equals(action)) {
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
            // than returning a payload with those fields null) keeps
            // handlePullRequestComment from ever being reached with a null pull
            // request when the comment body happens to mention the bot alias.
            return null;
        }
        Map<String, Object> comment = (Map<String, Object>) resource.get("comment");

        WebhookPayload.Repository repository =
                extractRepository((Map<String, Object>) pr.get("repository"), raw);
        if (repository == null) {
            return null;
        }

        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");
        payload.setSender(extractUser(comment != null ? (Map<String, Object>) comment.get("author") : null));
        payload.setComment(extractComment(comment));

        payload.setRepository(repository);
        payload.setPullRequest(extractPullRequest(pr));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());

            WebhookPayload.Issue issue = new WebhookPayload.Issue();
            issue.setNumber(payload.getPullRequest().getNumber());
            issue.setTitle(payload.getPullRequest().getTitle());
            WebhookPayload.IssuePullRequest ipr = new WebhookPayload.IssuePullRequest();
            issue.setPullRequest(ipr);
            payload.setIssue(issue);
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
     * every webhook-triggered call; {@code AzureDevopsWebhookToClientRoundTripTest} covers
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
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        String uniqueName = (String) user.get("uniqueName");
        owner.setLogin(uniqueName != null ? uniqueName : (String) user.get("displayName"));
        return owner;
    }

    /**
     * Maps the payload's reviewer entries to owners, dropping any that carry no identity.
     * <p>
     * {@code Stream.toList()} keeps nulls, and {@link #extractUser} returns one for an
     * entry that is absent or not an object, so an unfiltered list would hand
     * {@link #hasBotReviewer} a null to call {@code getLogin()} on. An empty list still
     * means "no reviewers"; only a {@code null} list means "this subscription does not
     * send them", which {@link #botReviewerAdded} must keep telling apart.
     */
    private List<WebhookPayload.Owner> extractReviewers(List<Map<String, Object>> reviewers) {
        if (reviewers == null) {
            return null;
        }
        return reviewers.stream()
                .map(this::extractUser)
                .filter(Objects::nonNull)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) {
            return null;
        }
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(toLong(comment.get("id")));
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
     * decided separately, by {@code AzureDevopsApiClient#scopesOrganization} — for the
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

    private boolean hasBotReviewer(Bot bot, WebhookPayload payload) {
        return bot.getUsername() != null
                && payload.getPullRequest() != null
                && payload.getPullRequest().getRequestedReviewers() != null
                && payload.getPullRequest().getRequestedReviewers().stream()
                .anyMatch(reviewer -> bot.getUsername().equalsIgnoreCase(reviewer.getLogin()));
    }
}
