package org.remus.giteabot.bitbucket;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
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
 */
@Slf4j
@Component
public class BitbucketWebhookHandler {

    private final BotWebhookService botWebhookService;

    public BitbucketWebhookHandler(BotWebhookService botWebhookService) {
        this.botWebhookService = botWebhookService;
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

        // Ignore events triggered by the bot itself
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            String senderLogin = webhookPayload.getSender() != null ? webhookPayload.getSender().getLogin() : "null";
            log.info("Ignoring Bitbucket webhook event from bot's own user. Bot username='{}', sender='{}'",
                    bot.getUsername(), senderLogin);
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);
        log.debug("Event passed all checks, processing {} with botAlias='{}'", eventKey, botAlias);

        return switch (eventKey) {
            case "pullrequest:created", "pullrequest:updated", "pullrequest:open" ->
                    handlePullRequestOpenedOrUpdated(bot, eventKey, webhookPayload, payload);
            case "pullrequest:fulfilled", "pullrequest:rejected", "pullrequest:merged", "pullrequest:declined" ->
                    handlePullRequestClosed(bot, webhookPayload);
            case "pullrequest:comment_created" ->
                    handlePullRequestComment(bot, webhookPayload, botAlias);
            default -> {
                log.warn("Unhandled Bitbucket event key: {}", eventKey);
                yield ResponseEntity.ok("ignored");
            }
        };
    }

    private ResponseEntity<String> handlePullRequestOpenedOrUpdated(Bot bot, String eventKey, WebhookPayload payload,
                                                                     Map<String, Object> raw) {
        if (("pullrequest:created".equals(eventKey) || "pullrequest:open".equals(eventKey))
                ? (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload))
                : botReviewerWasAdded(bot, raw)) {
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
                                                             String botAlias) {
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (body == null || !body.contains(botAlias)) {
            return ResponseEntity.ok("ignored");
        }

        // Bitbucket inline comments have a path set via the "inline" field
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

        Map<String, Object> ownerMap = (Map<String, Object>) repo.get("owner");
        if (ownerMap != null) {
            WebhookPayload.Owner owner = new WebhookPayload.Owner();
            String nickname = (String) ownerMap.get("nickname");
            String username = (String) ownerMap.get("username");
            String displayName = (String) ownerMap.get("display_name");
            owner.setLogin(nickname != null ? nickname : (username != null ? username : displayName));
            repository.setOwner(owner);
        } else {
            String fullName = (String) repo.get("full_name");
            if (fullName != null && fullName.contains("/")) {
                WebhookPayload.Owner owner = new WebhookPayload.Owner();
                owner.setLogin(fullName.substring(0, fullName.indexOf("/")));
                repository.setOwner(owner);
            }
        }

        return repository;
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

    private boolean hasBotReviewer(Bot bot, WebhookPayload payload) {
        return bot.getUsername() != null
                && payload.getPullRequest() != null
                && payload.getPullRequest().getRequestedReviewers() != null
                && payload.getPullRequest().getRequestedReviewers().stream()
                .anyMatch(reviewer -> bot.getUsername().equalsIgnoreCase(reviewer.getLogin()));
    }

    @SuppressWarnings("unchecked")
    private boolean botReviewerWasAdded(Bot bot, Map<String, Object> raw) {
        if (bot.getUsername() == null || !(raw.get("changes") instanceof Map<?, ?> changes)) {
            return false;
        }
        Object reviewers = changes.get("reviewers");
        if (!(reviewers instanceof Map<?, ?> reviewersChange)) {
            return false;
        }
        List<Map<String, Object>> current = (List<Map<String, Object>>) reviewersChange.get("new");
        List<Map<String, Object>> previous = (List<Map<String, Object>>) reviewersChange.get("old");
        return containsBitbucketUser(current, bot.getUsername()) && !containsBitbucketUser(previous, bot.getUsername());
    }

    private boolean containsBitbucketUser(List<Map<String, Object>> users, String username) {
        return users != null && users.stream()
                .map(this::extractBitbucketOwner)
                .anyMatch(user -> user != null && username.equalsIgnoreCase(user.getLogin()));
    }

}
