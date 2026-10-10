package org.remus.giteabot.bitbucket;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Handler for Bitbucket Cloud webhook events.
 * <p>
 * Receives Bitbucket Cloud webhook payloads and translates them into the common
 * {@link WebhookPayload} model used by the rest of the application, then
 * delegates to {@link BotWebhookService} for actual processing.
 * <p>
 * Bitbucket Cloud event types are delivered via the {@code X-Event-Key} header.
 * Supported events: pullrequest:created, pullrequest:open, pullrequest:updated,
 * pullrequest:fulfilled, pullrequest:rejected, pullrequest:merged,
 * pullrequest:declined, pullrequest:comment_created.
 * <p>
 * The bot's identity is the Bitbucket account behind the e-mail/API token configured on
 * the Git integration (the bot username is not used). Since webhook payloads carry no
 * e-mail addresses, that account is resolved via {@code GET /user} and matched against
 * payload users by {@code account_id}/{@code uuid}; mentions are detected via
 * Bitbucket's raw {@code @{account_id}} markup.
 */
@Slf4j
@Component
public class BitbucketWebhookHandler {

    private final BotWebhookService botWebhookService;
    private final GiteaClientFactory clientFactory;
    private final BotService botService;

    public BitbucketWebhookHandler(BotWebhookService botWebhookService, GiteaClientFactory clientFactory,
                                   BotService botService) {
        this.botWebhookService = botWebhookService;
        this.clientFactory = clientFactory;
        this.botService = botService;
    }

    /**
     * Handles a Bitbucket webhook event for the given bot.
     *
     * @param bot      the bot to process the webhook for
     * @param eventKey the Bitbucket event key from X-Event-Key header
     * @param payload  the raw webhook payload
     * @return response indicating the result of webhook processing
     */
    public ResponseEntity<String> handleWebhook(Bot bot, String eventKey, Map<String, Object> payload) {
        if (eventKey == null) {
            log.warn("Missing X-Event-Key header for Bitbucket webhook");
            return ResponseEntity.ok("ignored");
        }

        log.debug("Processing Bitbucket event: {} for bot '{}'", eventKey, bot.getName());

        WebhookPayload webhookPayload = translatePayload(eventKey, payload);
        if (webhookPayload == null) {
            log.warn("Could not translate Bitbucket payload for event key: {}", eventKey);
            return ResponseEntity.ok("ignored");
        }

        BitbucketAccount botAccount = resolveBotAccount(bot);
        if (botAccount == null) {
            return ResponseEntity.ok("ignored");
        }

        // Ignore events triggered by the bot itself
        if (isBotEvent(botAccount, payload)) {
            log.info("Ignoring Bitbucket webhook event from bot's own account '{}'", botAccount.accountId());
            return ResponseEntity.ok("ignored");
        }

        log.debug("Event passed all checks, processing {} with botAlias='{}'", eventKey, botAccount.mention());

        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:updated", "pullrequest:open" ->
                    handlePullRequestOpenedOrUpdated(bot, botAccount, eventKey, webhookPayload, payload);
            case "pullrequest:fulfilled", "pullrequest:rejected", "pullrequest:merged", "pullrequest:declined" ->
                    handlePullRequestClosed(bot, webhookPayload);
            case "pullrequest:comment_created" ->
                    handlePullRequestComment(bot, webhookPayload, botAccount);
            default -> {
                log.warn("Unhandled Bitbucket event key: {}", eventKey);
                yield ResponseEntity.ok("ignored");
            }
        };
    }

    /**
     * Resolves the Bitbucket account of the integration's e-mail/API token, or returns
     * {@code null} when it cannot be determined. Events are then ignored, because without
     * the bot's identity its own comments could re-trigger it. The failure is recorded as
     * the bot's last error so a misconfigured integration is visible in the admin UI.
     */
    private BitbucketAccount resolveBotAccount(Bot bot) {
        String error;
        try {
            BitbucketApiClient client = (BitbucketApiClient) clientFactory.getApiClient(bot.getGitIntegration());
            return client.getAuthenticatedAccount();
        } catch (RuntimeException e) {
            error = "Could not resolve the Bitbucket account for the configured Atlassian account email/API token: "
                    + e.getMessage();
        }
        log.error("[Bot '{}'] {}", bot.getName(), error);
        botService.recordError(bot, error);
        return null;
    }

    private ResponseEntity<String> handlePullRequestOpenedOrUpdated(Bot bot, BitbucketAccount botAccount, String eventKey,
                                                                     WebhookPayload payload, Map<String, Object> raw) {
        if (("pullrequest:created".equals(eventKey) || "pullrequest:open".equals(eventKey))
                ? (bot.isRunOnPrCreation() || hasBotReviewer(botAccount, raw))
                : botReviewerWasAdded(botAccount, raw)) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }
        return ResponseEntity.ok("ignored");
    }

    private ResponseEntity<String> handlePullRequestClosed(Bot bot, WebhookPayload payload) {
        botWebhookService.handlePrClosed(bot, payload);
        return ResponseEntity.ok("session closed");
    }

    private ResponseEntity<String> handlePullRequestComment(Bot bot, WebhookPayload payload,
                                                             BitbucketAccount botAccount) {
        String rawMention = botAccount.mention();
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (body == null || rawMention.isEmpty() || !body.contains(rawMention)) {
            return ResponseEntity.ok("ignored");
        }

        // Replace the opaque @{account_id} markup so downstream handlers and the AI see a readable name
        String botAlias = botAccount.readableMention();
        payload.getComment().setBody(body.replace(rawMention, botAlias));

        // Bitbucket inline comments have a path set via the "inline" field
        if (payload.getComment().getPath() != null) {
            botWebhookService.handleInlineComment(bot, payload);
            return ResponseEntity.ok("inline comment response triggered");
        }

        if (botWebhookService.isReviewAgainRequest(payload, botAlias)) {
            if (!botWebhookService.isPullRequestAuthor(payload)) {
                return ResponseEntity.ok("ignored");
            }
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }

        // General PR comment mentioning the bot
        botWebhookService.handleBotCommand(bot, payload);
        return ResponseEntity.ok("command received");
    }

    // ---- Bitbucket → WebhookPayload translation ----

    WebhookPayload translatePayload(String eventKey, Map<String, Object> raw) {
        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:open" -> translatePullRequestEvent(raw, "opened");
            case "pullrequest:updated" -> translatePullRequestEvent(raw, "synchronized");
            case "pullrequest:fulfilled", "pullrequest:merged", "pullrequest:rejected", "pullrequest:declined" -> translatePullRequestEvent(raw, "closed");
            case "pullrequest:comment_created" -> translatePullRequestCommentEvent(raw);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw, String action) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(action);
        payload.setSender(extractActor(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest(
                (Map<String, Object>) raw.get("pullrequest")));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestCommentEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");
        payload.setSender(extractActor(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest(
                (Map<String, Object>) raw.get("pullrequest")));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));

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
    private WebhookPayload.Owner extractActor(Map<String, Object> raw) {
        Map<String, Object> actor = (Map<String, Object>) raw.get("actor");
        if (actor == null) return null;
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        String nickname = (String) actor.get("nickname");
        owner.setLogin(nickname != null ? nickname : (String) actor.get("display_name"));
        return owner;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository extractRepository(Map<String, Object> raw) {
        Map<String, Object> repo = (Map<String, Object>) raw.get("repository");
        if (repo == null) return null;
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName((String) repo.get("name"));
        repository.setFullName((String) repo.get("full_name"));

        String uuid = (String) repo.get("uuid");
        if (uuid != null) {
            repository.setId((long) uuid.hashCode());
        }

        String workspace = resolveWorkspaceSlug(repo);
        if (workspace != null) {
            WebhookPayload.Owner owner = new WebhookPayload.Owner();
            owner.setLogin(workspace);
            repository.setOwner(owner);
        }

        return repository;
    }

    /**
     * Resolves the workspace slug used as {@code {workspace}} in API paths. The repository
     * owner is not reliable: for team-owned workspaces its legacy {@code username} can differ
     * from the slug (e.g. owner "postremus1" for workspace "postremus").
     */
    @SuppressWarnings("unchecked")
    private String resolveWorkspaceSlug(Map<String, Object> repo) {
        if (repo.get("workspace") instanceof Map<?, ?> workspace
                && workspace.get("slug") instanceof String slug && !slug.isBlank()) {
            return slug;
        }
        if (repo.get("full_name") instanceof String fullName && fullName.contains("/")) {
            return fullName.substring(0, fullName.indexOf('/'));
        }
        Map<String, Object> ownerMap = (Map<String, Object>) repo.get("owner");
        if (ownerMap == null) {
            return null;
        }
        String nickname = (String) ownerMap.get("nickname");
        String username = (String) ownerMap.get("username");
        return nickname != null ? nickname : (username != null ? username : (String) ownerMap.get("display_name"));
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest extractPullRequest(Map<String, Object> pr) {
        if (pr == null) return null;
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setId(toLong(pr.get("id")));
        pullRequest.setNumber(toLong(pr.get("id")));
        pullRequest.setTitle((String) pr.get("title"));
        pullRequest.setBody((String) pr.get("description"));
        pullRequest.setState((String) pr.get("state"));
        pullRequest.setUser(extractBitbucketOwner((Map<String, Object>) pr.get("author")));
        pullRequest.setRequestedReviewers(extractBitbucketOwners((List<Map<String, Object>>) pr.get("reviewers")));

        Map<String, Object> source = (Map<String, Object>) pr.get("source");
        if (source != null) {
            WebhookPayload.Head head = new WebhookPayload.Head();
            Map<String, Object> branch = (Map<String, Object>) source.get("branch");
            if (branch != null) {
                head.setRef((String) branch.get("name"));
            }
            Map<String, Object> commit = (Map<String, Object>) source.get("commit");
            if (commit != null) {
                head.setSha((String) commit.get("hash"));
            }
            pullRequest.setHead(head);
        }

        Map<String, Object> destination = (Map<String, Object>) pr.get("destination");
        if (destination != null) {
            WebhookPayload.Head base = new WebhookPayload.Head();
            Map<String, Object> branch = (Map<String, Object>) destination.get("branch");
            if (branch != null) {
                base.setRef((String) branch.get("name"));
            }
            Map<String, Object> commit = (Map<String, Object>) destination.get("commit");
            if (commit != null) {
                base.setSha((String) commit.get("hash"));
            }
            pullRequest.setBase(base);
        }

        pullRequest.setMerged("MERGED".equals(pr.get("state")));

        return pullRequest;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(toLong(comment.get("id")));

        Map<String, Object> content = (Map<String, Object>) comment.get("content");
        if (content != null) {
            c.setBody((String) content.get("raw"));
        }

        Map<String, Object> user = (Map<String, Object>) comment.get("user");
        if (user != null) {
            WebhookPayload.Owner u = new WebhookPayload.Owner();
            String nickname = (String) user.get("nickname");
            u.setLogin(nickname != null ? nickname : (String) user.get("display_name"));
            c.setUser(u);
        }

        Map<String, Object> inline = (Map<String, Object>) comment.get("inline");
        if (inline != null) {
            c.setPath((String) inline.get("path"));
            c.setLine(inline.get("to") instanceof Number n ? n.intValue() : null);
        }

        return c;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private WebhookPayload.Owner extractBitbucketOwner(Map<String, Object> owner) {
        if (owner == null) return null;
        WebhookPayload.Owner o = new WebhookPayload.Owner();
        String nickname = (String) owner.get("nickname");
        o.setLogin(nickname != null ? nickname : (String) owner.get("display_name"));
        return o;
    }

    private List<WebhookPayload.Owner> extractBitbucketOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream().map(this::extractBitbucketOwner).toList();
    }

    private boolean isBotEvent(BitbucketAccount botAccount, Map<String, Object> raw) {
        if (botAccount.isSameAccount(asMap(raw.get("actor")))) {
            return true;
        }
        Map<?, ?> comment = asMap(raw.get("comment"));
        return comment != null && botAccount.isSameAccount(asMap(comment.get("user")));
    }

    private boolean hasBotReviewer(BitbucketAccount botAccount, Map<String, Object> raw) {
        Map<?, ?> pullRequest = asMap(raw.get("pullrequest"));
        return pullRequest != null && containsAccount(pullRequest.get("reviewers"), botAccount);
    }

    private boolean botReviewerWasAdded(BitbucketAccount botAccount, Map<String, Object> raw) {
        Map<?, ?> changes = asMap(raw.get("changes"));
        Map<?, ?> reviewersChange = changes != null ? asMap(changes.get("reviewers")) : null;
        if (reviewersChange == null) {
            return false;
        }
        return containsAccount(reviewersChange.get("new"), botAccount)
                && !containsAccount(reviewersChange.get("old"), botAccount);
    }

    private boolean containsAccount(Object users, BitbucketAccount account) {
        return users instanceof List<?> list && list.stream()
                .anyMatch(user -> account.isSameAccount(asMap(user)));
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

}
