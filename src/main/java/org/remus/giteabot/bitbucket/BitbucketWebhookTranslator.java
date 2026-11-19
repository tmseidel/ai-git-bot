package org.remus.giteabot.bitbucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.bitbucket.model.BitbucketAccount;
import org.remus.giteabot.event.BotAccountResolver;
import org.remus.giteabot.event.BotIdentity;
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
 * The bot's identity is the Bitbucket account the Git integration's API token authenticates
 * as, resolved via {@link BotAccountResolver} and matched against payload users by
 * {@code account_id} only (a user object carrying just a {@code uuid} is not the bot).
 * Mentions are detected via Bitbucket's raw {@code @{account_id}} markup, which is
 * rewritten to a readable name before the payload is handed on.
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

    private final BotAccountResolver botAccountResolver;
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

        BotIdentity identity = botAccountResolver.resolve(bot, null).orElse(null);
        if (identity == null) {
            return WebhookEvent.ignored(BotAccountResolver.UNRESOLVED);
        }

        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:open" -> {
                // Seed the head and the reviewer list, so a pullrequest:updated for the same
                // commit and reviewers is taken neither for a push nor for a review request.
                stateTracker.headMoved(bot, webhookPayload);
                botReviewerAdded(bot, webhookPayload, identity);
                yield event(WebhookEventKind.PR_OPENED, webhookPayload, identity);
            }
            case "pullrequest:updated" -> classifyUpdate(bot, webhookPayload, identity);
            case "pullrequest:fulfilled", "pullrequest:rejected", "pullrequest:merged", "pullrequest:declined" -> {
                stateTracker.markClosed(bot, webhookPayload);
                yield event(WebhookEventKind.PR_CLOSED, webhookPayload, identity);
            }
            case "pullrequest:comment_created" -> classifyComment(webhookPayload, identity);
            default -> WebhookEvent.ignored("unhandled Bitbucket event key '" + eventKey + "'");
        };
    }

    // ---- Classification ----

    private WebhookEvent classifyUpdate(Bot bot, WebhookPayload payload, BotIdentity identity) {
        // Both record what they saw, so neither may be short-circuited away.
        boolean headMoved = stateTracker.headMoved(bot, payload);
        boolean reviewerAdded = botReviewerAdded(bot, payload, identity);
        if (reviewerAdded) {
            return event(WebhookEventKind.PR_REVIEWER_ADDED, payload, identity);
        }
        if (headMoved) {
            return event(WebhookEventKind.PR_SYNCHRONIZED, payload, identity);
        }
        return WebhookEvent.ignored("pull request update without new commits or the bot being added as reviewer");
    }

    private WebhookEvent classifyComment(WebhookPayload payload, BotIdentity identity) {
        WebhookPayload.Comment comment = payload.getComment();
        if (comment == null || payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Bitbucket comment event without comment or pull request");
        }
        boolean inline = comment.getPath() != null;
        return event(inline ? WebhookEventKind.PR_INLINE_COMMENT : WebhookEventKind.PR_COMMENT, payload, identity);
    }

    /**
     * Whether the bot has joined the pull request's reviewers since the previous event.
     * A payload without a reviewer list leaves the recorded state untouched, so that the
     * omission is not read as a removal.
     */
    private boolean botReviewerAdded(Bot bot, WebhookPayload payload, BotIdentity identity) {
        if (payload.getPullRequest() == null || payload.getPullRequest().getRequestedReviewers() == null) {
            return false;
        }
        return stateTracker.reviewerAdded(bot, payload, identity.isReviewer(payload));
    }

    /**
     * Builds a non-ignored event. Users are matched by {@code account_id}, which the payload's
     * owners carry as their id. Mentions are not by name either: Bitbucket stores them as
     * {@code @{account_id}} markup, which is replaced with the readable name, so downstream
     * handlers and the AI see something meaningful.
     */
    private WebhookEvent event(WebhookEventKind kind, WebhookPayload payload, BotIdentity identity) {
        BitbucketAccount botAccount = BitbucketAccount.of(identity.account());
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
                .fromBot(identity.triggered(payload))
                .botIsReviewer(identity.isReviewer(payload))
                .botMentioned(mentioned)
                .botAlias(readableMention)
                .build();
    }

    // ---- Bitbucket → WebhookPayload translation ----

    WebhookPayload translatePayload(String eventKey, Map<String, Object> raw) {
        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:open", "pullrequest:updated",
                 "pullrequest:fulfilled", "pullrequest:merged", "pullrequest:rejected", "pullrequest:declined" ->
                    translatePullRequestEvent(raw);
            case "pullrequest:comment_created" -> translatePullRequestCommentEvent(raw);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
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
        WebhookPayload payload = translatePullRequestEvent(raw);
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

    /** Extracts the branch and commit of a pull request's {@code source} or {@code destination}. */
    private WebhookPayload.Head extractRef(Map<?, ?> side) {
        if (side == null) return null;
        WebhookPayload.Head head = new WebhookPayload.Head();
        if (side.get("branch") instanceof Map<?, ?> branch) {
            head.setRef((String) branch.get("name"));
        }
        if (side.get("commit") instanceof Map<?, ?> commit) {
            head.setSha((String) commit.get("hash"));
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
        // account_id is what the bot is recognised by; the nickname is only the login.
        String nickname = (String) owner.get("nickname");
        return WebhookPayload.Owner.of(owner.get("account_id"),
                nickname != null ? nickname : (String) owner.get("display_name"));
    }

    private List<WebhookPayload.Owner> extractBitbucketOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream().map(this::extractBitbucketOwner).toList();
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

}
