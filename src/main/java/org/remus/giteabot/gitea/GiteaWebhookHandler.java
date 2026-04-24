package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Handler for Gitea webhook events.
 * <p>
 * Receives raw webhook payloads and processes them using the common
 * {@link WebhookPayload} model. Gitea payloads map directly to the WebhookPayload
 * structure, so minimal translation is needed.
 */
@Slf4j
@Component
public class GiteaWebhookHandler {

    private final BotWebhookService botWebhookService;

    public GiteaWebhookHandler(BotWebhookService botWebhookService) {
        this.botWebhookService = botWebhookService;
    }

    /**
     * Handles a Gitea webhook event for the given bot.
     *
     * @param bot     the bot to process the webhook for
     * @param payload the raw webhook payload
     * @return response indicating the result of webhook processing
     */
    public ResponseEntity<String> handleWebhook(Bot bot, Map<String, Object> payload) {
        // Convert the raw payload to WebhookPayload
        WebhookPayload webhookPayload = translatePayload(payload);

        log.debug("Gitea payload state: action={}, pullRequest={}, comment={}, issue={}, sender={}",
                webhookPayload.getAction(),
                webhookPayload.getPullRequest() != null ? "present" : "null",
                webhookPayload.getComment() != null ? "present" : "null",
                webhookPayload.getIssue() != null ? "present" : "null",
                webhookPayload.getSender() != null ? webhookPayload.getSender().getLogin() : "null");

        return handleBotWebhookEvent(bot, webhookPayload);
    }

    // ---- Gitea → WebhookPayload translation ----

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePayload(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction((String) raw.get("action"));
        payload.setNumber(toLong(raw.get("number")));
        payload.setSender(extractOwner((Map<String, Object>) raw.get("sender")));
        payload.setRepository(extractRepository((Map<String, Object>) raw.get("repository")));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        payload.setReview(extractReview((Map<String, Object>) raw.get("review")));
        return payload;
    }

    // ---- Extraction helpers ----

    private WebhookPayload.Owner extractOwner(Map<String, Object> owner) {
        if (owner == null) return null;
        WebhookPayload.Owner o = new WebhookPayload.Owner();
        String login = (String) owner.get("login");
        // Fallback to username if login is not present
        o.setLogin(login != null ? login : (String) owner.get("username"));
        return o;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository extractRepository(Map<String, Object> repo) {
        if (repo == null) return null;
        WebhookPayload.Repository r = new WebhookPayload.Repository();
        r.setId(toLong(repo.get("id")));
        r.setName((String) repo.get("name"));
        r.setFullName((String) repo.get("full_name"));
        r.setOwner(extractOwner((Map<String, Object>) repo.get("owner")));
        return r;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest extractPullRequest(Map<String, Object> pr) {
        if (pr == null) return null;
        WebhookPayload.PullRequest p = new WebhookPayload.PullRequest();
        p.setId(toLong(pr.get("id")));
        p.setNumber(toLong(pr.get("number")));
        p.setTitle((String) pr.get("title"));
        p.setBody((String) pr.get("body"));
        p.setState((String) pr.get("state"));
        p.setMerged(pr.get("merged") instanceof Boolean b ? b : null);
        p.setDiffUrl((String) pr.get("diff_url"));

        Map<String, Object> head = (Map<String, Object>) pr.get("head");
        if (head != null) {
            WebhookPayload.Head h = new WebhookPayload.Head();
            h.setRef((String) head.get("ref"));
            h.setSha((String) head.get("sha"));
            p.setHead(h);
        }
        Map<String, Object> base = (Map<String, Object>) pr.get("base");
        if (base != null) {
            WebhookPayload.Head b = new WebhookPayload.Head();
            b.setRef((String) base.get("ref"));
            b.setSha((String) base.get("sha"));
            p.setBase(b);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Issue extractIssue(Map<String, Object> issue) {
        if (issue == null) return null;
        WebhookPayload.Issue i = new WebhookPayload.Issue();
        i.setNumber(toLong(issue.get("number")));
        i.setTitle((String) issue.get("title"));
        i.setBody((String) issue.get("body"));
        i.setAssignee(extractOwner((Map<String, Object>) issue.get("assignee")));

        // Check for pull_request link
        if (issue.get("pull_request") != null) {
            WebhookPayload.IssuePullRequest ipr = new WebhookPayload.IssuePullRequest();
            Map<String, Object> prLink = (Map<String, Object>) issue.get("pull_request");
            ipr.setMerged(prLink.get("merged") instanceof Boolean b ? b : null);
            i.setPullRequest(ipr);
        }

        // Assignees
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) issue.get("assignees");
        if (assignees != null) {
            i.setAssignees(assignees.stream()
                    .map(this::extractOwner)
                    .toList());
        }
        return i;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(toLong(comment.get("id")));
        c.setBody((String) comment.get("body"));
        c.setPath((String) comment.get("path"));
        c.setDiffHunk((String) comment.get("diff_hunk"));
        c.setLine(comment.get("line") instanceof Number n ? n.intValue() : null);
        c.setPullRequestReviewId(toLong(comment.get("pull_request_review_id")));
        c.setUser(extractOwner((Map<String, Object>) comment.get("user")));
        return c;
    }

    private WebhookPayload.Review extractReview(Map<String, Object> review) {
        if (review == null) return null;
        WebhookPayload.Review r = new WebhookPayload.Review();
        r.setId(toLong(review.get("id")));
        r.setType((String) review.get("type"));
        r.setContent((String) review.get("content"));
        return r;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * Routes a webhook event for a specific persisted bot through its own AI and Git integrations.
     */
    private ResponseEntity<String> handleBotWebhookEvent(Bot bot, WebhookPayload payload) {
        // Ignore events triggered by the bot itself
        if (botWebhookService.isBotUser(bot, payload)) {
            log.debug("Ignoring Gitea webhook event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);

        // Handle inline review comments
        if (payload.getComment() != null && payload.getComment().getPath() != null
                && !payload.getComment().getPath().isBlank()) {
            if ("created".equals(payload.getAction())
                    && payload.getComment().getBody() != null
                    && payload.getComment().getBody().contains(botAlias)) {
                botWebhookService.handleInlineComment(bot, payload);
                return ResponseEntity.ok("inline comment response triggered");
            }
            log.debug("Ignoring non-command inline comment or non-created action for comment {} on path '{}'",
                    payload.getComment().getId(),
                    payload.getComment().getPath());
            return ResponseEntity.ok("ignored");
        }

        // Handle issue/PR comments with bot mention
        if (payload.getComment() != null && payload.getIssue() != null) {
            if (!"created".equals(payload.getAction())) {
                log.debug("Ignoring non-created action for issue comment {}", payload.getComment().getId());
                return ResponseEntity.ok("ignored");
            }
            String body = payload.getComment().getBody();
            if (body == null || !body.contains(botAlias)) {
                log.debug("Issue comment {} does not mention bot alias '{}', ignoring",
                        payload.getComment().getId(), botAlias);
                return ResponseEntity.ok("ignored");
            }
            if (payload.getPullRequest() != null) {
                // Comment on a PR discussion thread — let BotWebhookService decide whether to
                // route to the agent (session exists) or the code-review handler (no session).
                botWebhookService.handlePrComment(bot, payload);
                return ResponseEntity.ok("pr comment received");
            }
            // Comment on a regular issue — always route to the agent
            botWebhookService.handleIssueComment(bot, payload);
            return ResponseEntity.ok("issue comment received");
        }

        // Handle issue assignment events (agent feature)
        if ("assigned".equals(payload.getAction()) && payload.getIssue() != null
                && payload.getPullRequest() == null) {
            // Check if the assignee is the bot
            if (payload.getIssue().getAssignee() != null
                    && bot.getUsername() != null
                    && bot.getUsername().equalsIgnoreCase(payload.getIssue().getAssignee().getLogin())) {
                botWebhookService.handleIssueAssigned(bot, payload);
                return ResponseEntity.ok("agent triggered");
            }
            return ResponseEntity.ok("ignored");
        }

        // Handle PR events
        if (payload.getPullRequest() == null) {
            log.debug("Gitea webhook event is not related to a pull request, ignoring");
            return ResponseEntity.ok("ignored");
        }

        String action = payload.getAction();

        if ("reviewed".equals(action) && payload.getReview() != null) {
            botWebhookService.handleReviewSubmitted(bot, payload);
            return ResponseEntity.ok("review comments processing triggered");
        }

        if ("closed".equals(action)) {
            botWebhookService.handlePrClosed(bot, payload);
            return ResponseEntity.ok("session closed");
        }

        if ("opened".equals(action) || "synchronized".equals(action)) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }

        log.debug("Unhandled Gitea PR action '{}', ignoring", action);
        return ResponseEntity.ok("ignored");
    }
}



