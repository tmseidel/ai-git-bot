package org.remus.giteabot.bitbucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.PullRequestStateTracker;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.util.BotMention;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Translates Bitbucket Cloud webhooks into provider-neutral {@link WebhookEvent}s.
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
 * Bitbucket's raw {@code @{account_id}} markup, which is rewritten to a readable name
 * before the payload is handed on.
 * <p>
 * {@code pullrequest:updated} fires for pushes, edits and reviewer changes alike and says
 * nothing about what changed, so a push is recognised by the source commit having moved
 * since the last event, and a review request by the bot having joined the reviewers
 * since then ({@link PullRequestStateTracker}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitbucketWebhookTranslator implements WebhookTranslator {

    static final String EVENT_HEADER = "X-Event-Key";

    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final BotService botService;
    private final PullRequestStateTracker stateTracker;

    @Override
    public RepositoryType providerType() {
        return RepositoryType.BITBUCKET;
    }

    /** Bitbucket Cloud signs the body: {@code X-Hub-Signature: sha256=<hex hmac>}. */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches("sha256=" + WebhookSignatures.hmacSha256Hex(signingSecret, rawBody),
                headers.get("X-Hub-Signature"));
    }

    @Override
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventKey = headers.get(EVENT_HEADER);
        if (eventKey == null) {
            log.warn("Missing {} header for Bitbucket webhook", EVENT_HEADER);
            return WebhookEvent.ignored("missing " + EVENT_HEADER + " header");
        }

        log.debug("Processing Bitbucket event: {} for bot '{}'", eventKey, bot.getName());

        WebhookPayload webhookPayload = translatePayload(eventKey, payload);
        if (webhookPayload == null) {
            return WebhookEvent.ignored("unhandled Bitbucket event key '" + eventKey + "'");
        }

        BitbucketAccount botAccount = resolveBotAccount(bot);
        if (botAccount == null) {
            return WebhookEvent.ignored("Bitbucket bot account could not be resolved");
        }

        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:open" -> {
                // Seed the head and the reviewer list, so a pullrequest:updated for the same
                // commit and reviewers is taken neither for a push nor for a review request.
                stateTracker.headMoved(bot, webhookPayload);
                botReviewerAdded(bot, webhookPayload, botAccount, payload);
                yield event(WebhookEventKind.PR_OPENED, webhookPayload, botAccount, payload);
            }
            case "pullrequest:updated" -> classifyUpdate(bot, webhookPayload, botAccount, payload);
            case "pullrequest:fulfilled", "pullrequest:rejected", "pullrequest:merged", "pullrequest:declined" -> {
                stateTracker.markClosed(bot, webhookPayload);
                yield event(WebhookEventKind.PR_CLOSED, webhookPayload, botAccount, payload);
            }
            case "pullrequest:comment_created" -> classifyComment(webhookPayload, botAccount, payload);
            default -> WebhookEvent.ignored("unhandled Bitbucket event key '" + eventKey + "'");
        };
    }

    // ---- Classification ----

    private WebhookEvent classifyUpdate(Bot bot, WebhookPayload payload, BitbucketAccount botAccount,
                                        Map<String, Object> raw) {
        // Both record what they saw, so neither may be short-circuited away.
        boolean headMoved = stateTracker.headMoved(bot, payload);
        boolean reviewerAdded = botReviewerAdded(bot, payload, botAccount, raw);
        if (reviewerAdded) {
            payload.setAction("review_requested");
            return event(WebhookEventKind.PR_REVIEWER_ADDED, payload, botAccount, raw);
        }
        if (headMoved) {
            return event(WebhookEventKind.PR_SYNCHRONIZED, payload, botAccount, raw);
        }
        return WebhookEvent.ignored("pull request update without new commits or the bot being added as reviewer");
    }

    private WebhookEvent classifyComment(WebhookPayload payload, BitbucketAccount botAccount, Map<String, Object> raw) {
        WebhookPayload.Comment comment = payload.getComment();
        if (comment == null || payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Bitbucket comment event without comment or pull request");
        }
        boolean inline = comment.getPath() != null;
        return event(inline ? WebhookEventKind.PR_INLINE_COMMENT : WebhookEventKind.PR_COMMENT,
                payload, botAccount, raw);
    }

    /**
     * Builds a non-ignored event. Identity is matched by Bitbucket account, not username:
     * the bot's own events, its reviewer status and its mention all go by account id/uuid.
     * A mention's opaque {@code @{account_id}} markup is replaced with the readable name, so
     * downstream handlers and the AI see something meaningful.
     */
    private WebhookEvent event(WebhookEventKind kind, WebhookPayload payload, BitbucketAccount botAccount,
                               Map<String, Object> raw) {
        String rawMention = botAccount.mention();
        String readableMention = botAccount.readableMention();
        WebhookPayload.Comment comment = payload.getComment();
        boolean mentioned = comment != null && BotMention.isMentioned(comment.getBody(), rawMention);
        if (mentioned) {
            comment.setBody(BotMention.replaceMention(comment.getBody(), rawMention, readableMention));
        }
        return WebhookEvent.builder()
                .kind(kind)
                .payload(payload)
                .fromBot(isBotEvent(botAccount, raw))
                .botIsReviewer(hasBotReviewer(botAccount, raw))
                .botMentioned(mentioned)
                .botAlias(readableMention)
                .build();
    }

    /**
     * Resolves the Bitbucket account of the integration's e-mail/API token, or returns
     * {@code null} when it cannot be determined. Events are then ignored, because without
     * the bot's identity its own comments could re-trigger it. The failure is recorded as
     * the bot's last error so a misconfigured integration is visible in the admin UI.
     */
    private BitbucketAccount resolveBotAccount(Bot bot) {
        try {
            BitbucketApiClient client = (BitbucketApiClient) repositoryPlatformFactory.getApiClient(bot.getGitIntegration());
            return client.getAuthenticatedAccount();
        } catch (RuntimeException e) {
            String error = "Could not resolve the Bitbucket account for the configured Atlassian account email/API token: "
                    + e.getMessage();
            log.error("[Bot '{}'] {}", bot.getName(), error);
            botService.recordError(bot, error);
            return null;
        }
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
        payload.setSender(extractBitbucketOwner((Map<String, Object>) raw.get("actor")));
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
        WebhookPayload payload = translatePullRequestEvent(raw, "created");
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        return payload;
    }

    // ---- Extraction helpers ----

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

        // The repository name can be a display label; full_name contains its API slug.
        String fullName = (String) repo.get("full_name");
        if (fullName != null && fullName.contains("/")) {
            repository.setName(fullName.substring(fullName.indexOf('/') + 1));
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

        pullRequest.setHead(extractRef(asMap(pr.get("source"))));
        pullRequest.setBase(extractRef(asMap(pr.get("destination"))));
        pullRequest.setMerged("MERGED".equals(pr.get("state")));

        return pullRequest;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(CommentId.NumericCommentId.of(toLong(comment.get("id"))));

        Map<String, Object> content = (Map<String, Object>) comment.get("content");
        if (content != null) {
            c.setBody((String) content.get("raw"));
        }

        c.setUser(extractBitbucketOwner((Map<String, Object>) comment.get("user")));

        Map<String, Object> inline = (Map<String, Object>) comment.get("inline");
        if (inline != null) {
            c.setPath((String) inline.get("path"));
            c.setLine(inline.get("to") instanceof Number n ? n.intValue() : null);
        }

        return c;
    }

    /** Extracts the branch, commit and repository of a pull request's {@code source} or {@code destination}. */
    private WebhookPayload.Head extractRef(Map<?, ?> side) {
        if (side == null) return null;
        WebhookPayload.Head head = new WebhookPayload.Head();
        if (side.get("branch") instanceof Map<?, ?> branch) {
            head.setRef((String) branch.get("name"));
        }
        if (side.get("commit") instanceof Map<?, ?> commit) {
            head.setSha((String) commit.get("hash"));
        }
        if (side.get("repository") instanceof Map<?, ?> repository
                && repository.get("full_name") instanceof String fullName) {
            head.setRepositoryFullName(fullName);
        }
        return head;
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

    /**
     * Whether the bot has joined the pull request's reviewers since the previous event.
     * A payload without a reviewer list leaves the recorded state untouched, so that the
     * omission is not read as a removal.
     */
    private boolean botReviewerAdded(Bot bot, WebhookPayload payload, BitbucketAccount botAccount,
                                     Map<String, Object> raw) {
        Map<?, ?> pullRequest = asMap(raw.get("pullrequest"));
        if (pullRequest == null || !(pullRequest.get("reviewers") instanceof List<?>)) {
            return false;
        }
        return stateTracker.reviewerAdded(bot, payload, hasBotReviewer(botAccount, raw));
    }

    private boolean containsAccount(Object users, BitbucketAccount account) {
        return users instanceof List<?> list && list.stream()
                .anyMatch(user -> account.isSameAccount(asMap(user)));
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

}
