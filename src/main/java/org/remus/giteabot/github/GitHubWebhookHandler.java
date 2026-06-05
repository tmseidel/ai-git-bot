package org.remus.giteabot.github;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Handler for GitHub webhook events.
 * <p>
 * Receives GitHub webhook payloads and translates them into the common
 * {@link WebhookPayload} model used by the rest of the application, then
 * delegates to {@link BotWebhookService} for actual processing.
 * <p>
 * GitHub event types are delivered via the {@code X-GitHub-Event} header.
 * Supported events: pull_request, issue_comment, pull_request_review,
 * pull_request_review_comment, issues.
 */
@Slf4j
@Component
public class GitHubWebhookHandler {

    private final BotWebhookService botWebhookService;

    public GitHubWebhookHandler(BotWebhookService botWebhookService) {
        this.botWebhookService = botWebhookService;
    }

    /**
     * Handles a GitHub webhook event for the given bot.
     *
     * @param bot       the bot to process the webhook for
     * @param eventType the GitHub event type from X-GitHub-Event header
     * @param payload   the raw webhook payload
     * @return response indicating the result of webhook processing
     */
    public ResponseEntity<String> handleWebhook(Bot bot, String eventType, Map<String, Object> payload) {
        if (eventType == null) {
            log.warn("Missing X-GitHub-Event header for GitHub webhook");
            return ResponseEntity.ok("ignored");
        }

        log.debug("Processing GitHub event: {} for bot '{}'", eventType, bot.getName());

        WebhookPayload webhookPayload = translatePayload(eventType, payload);
        if (webhookPayload == null) {
            log.warn("Could not translate GitHub payload for event type: {}", eventType);
            return ResponseEntity.ok("ignored");
        }

        // Ignore events triggered by the bot itself
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            log.debug("Ignoring GitHub webhook event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);

        return switch (eventType) {
            case "pull_request" -> handlePullRequestEvent(bot, webhookPayload);
            case "issue_comment" -> handleIssueCommentEvent(bot, webhookPayload, botAlias);
            case "pull_request_review" -> handlePullRequestReviewEvent(bot, webhookPayload);
            case "pull_request_review_comment" -> handlePullRequestReviewCommentEvent(bot, webhookPayload, botAlias);
            case "issues" -> handleIssuesEvent(bot, webhookPayload);
            default -> {
                log.debug("Unhandled GitHub event type: {}", eventType);
                yield ResponseEntity.ok("ignored");
            }
        };
    }

    private ResponseEntity<String> handlePullRequestEvent(Bot bot, WebhookPayload payload) {
        String action = payload.getAction();
        if (("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
                || ("review_requested".equals(action) && isRequestedReviewer(bot, payload))) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }
        if ("closed".equals(action)) {
            botWebhookService.handlePrClosed(bot, payload);
            return ResponseEntity.ok("session closed");
        }
        return ResponseEntity.ok("ignored");
    }

    private ResponseEntity<String> handleIssueCommentEvent(Bot bot, WebhookPayload payload,
                                                            String botAlias) {
        if (!"created".equals(payload.getAction())) {
            return ResponseEntity.ok("ignored");
        }
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (body == null || !body.contains(botAlias)) {
            return ResponseEntity.ok("ignored");
        }
        // Check if the comment is on a PR (issue with pull_request link)
        if (payload.getIssue() != null && payload.getIssue().getPullRequest() != null) {
            botWebhookService.handleBotCommand(bot, payload);
            return ResponseEntity.ok("command received");
        }
        // Plain issue comment
        botWebhookService.handleIssueComment(bot, payload);
        return ResponseEntity.ok("issue comment received");
    }

    private ResponseEntity<String> handlePullRequestReviewEvent(Bot bot, WebhookPayload payload) {
        if (!"reviewed".equals(payload.getAction())) {
            return ResponseEntity.ok("ignored");
        }
        botWebhookService.handleReviewSubmitted(bot, payload);
        return ResponseEntity.ok("review comments processing triggered");
    }

    private ResponseEntity<String> handlePullRequestReviewCommentEvent(Bot bot,
                                                                        WebhookPayload payload,
                                                                        String botAlias) {
        if (!"created".equals(payload.getAction())) {
            return ResponseEntity.ok("ignored");
        }
        if (payload.getComment() != null && payload.getComment().getBody() != null
                && payload.getComment().getBody().contains(botAlias)) {
            botWebhookService.handleInlineComment(bot, payload);
            return ResponseEntity.ok("inline comment response triggered");
        }
        return ResponseEntity.ok("ignored");
    }

    private ResponseEntity<String> handleIssuesEvent(Bot bot, WebhookPayload payload) {
        if (!"assigned".equals(payload.getAction())) {
            return ResponseEntity.ok("ignored");
        }
        if (payload.getIssue() != null
                && payload.getIssue().getAssignee() != null
                && bot.getUsername() != null
                && bot.getUsername().equalsIgnoreCase(payload.getIssue().getAssignee().getLogin())) {
            botWebhookService.handleIssueAssigned(bot, payload);
            return ResponseEntity.ok("agent triggered");
        }
        return ResponseEntity.ok("ignored");
    }

    // ---- GitHub → WebhookPayload translation ----

    WebhookPayload translatePayload(String eventType, Map<String, Object> raw) {
        return switch (eventType) {
            case "pull_request" -> translatePullRequestEvent(raw);
            case "issue_comment" -> translateIssueCommentEvent(raw);
            case "pull_request_review" -> translatePullRequestReviewEvent(raw);
            case "pull_request_review_comment" -> translatePullRequestReviewCommentEvent(raw);
            case "issues" -> translateIssuesEvent(raw);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(mapAction((String) raw.get("action")));
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setRequestedReviewer(extractOwner((Map<String, Object>) raw.get("requested_reviewer")));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translateIssueCommentEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction((String) raw.get("action"));
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestReviewEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(mapReviewAction((String) raw.get("action")));
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setReview(extractReview((Map<String, Object>) raw.get("review")));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestReviewCommentEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction((String) raw.get("action"));
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setComment(extractReviewComment((Map<String, Object>) raw.get("comment")));
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

    @SuppressWarnings("unchecked")
    private WebhookPayload translateIssuesEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction((String) raw.get("action"));
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        return payload;
    }

    // ---- Extraction helpers ----

    @SuppressWarnings("unchecked")
    private WebhookPayload.Owner extractSender(Map<String, Object> raw) {
        Map<String, Object> sender = (Map<String, Object>) raw.get("sender");
        if (sender == null) return null;
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin((String) sender.get("login"));
        return owner;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository extractRepository(Map<String, Object> raw) {
        Map<String, Object> repo = (Map<String, Object>) raw.get("repository");
        if (repo == null) return null;
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setId(toLong(repo.get("id")));
        repository.setName((String) repo.get("name"));
        repository.setFullName((String) repo.get("full_name"));
        Map<String, Object> ownerMap = (Map<String, Object>) repo.get("owner");
        if (ownerMap != null) {
            WebhookPayload.Owner owner = new WebhookPayload.Owner();
            owner.setLogin((String) ownerMap.get("login"));
            repository.setOwner(owner);
        }
        return repository;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest extractPullRequest(Map<String, Object> pr) {
        if (pr == null) return null;
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setId(toLong(pr.get("id")));
        pullRequest.setNumber(toLong(pr.get("number")));
        pullRequest.setTitle((String) pr.get("title"));
        pullRequest.setBody((String) pr.get("body"));
        pullRequest.setState((String) pr.get("state"));
        pullRequest.setMerged(pr.get("merged") instanceof Boolean b ? b : null);
        pullRequest.setDiffUrl((String) pr.get("diff_url"));
        pullRequest.setUser(extractOwner((Map<String, Object>) pr.get("user")));
        pullRequest.setRequestedReviewers(extractOwners((List<Map<String, Object>>) pr.get("requested_reviewers")));
        Map<String, Object> head = (Map<String, Object>) pr.get("head");
        if (head != null) {
            WebhookPayload.Head h = new WebhookPayload.Head();
            h.setRef((String) head.get("ref"));
            h.setSha((String) head.get("sha"));
            pullRequest.setHead(h);
        }
        Map<String, Object> base = (Map<String, Object>) pr.get("base");
        if (base != null) {
            WebhookPayload.Head b = new WebhookPayload.Head();
            b.setRef((String) base.get("ref"));
            b.setSha((String) base.get("sha"));
            pullRequest.setBase(b);
        }
        return pullRequest;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(toLong(comment.get("id")));
        c.setBody((String) comment.get("body"));
        Map<String, Object> user = (Map<String, Object>) comment.get("user");
        if (user != null) {
            WebhookPayload.Owner u = new WebhookPayload.Owner();
            u.setLogin((String) user.get("login"));
            c.setUser(u);
        }
        return c;
    }

    private WebhookPayload.Comment extractReviewComment(Map<String, Object> comment) {
        WebhookPayload.Comment c = extractComment(comment);
        if (c == null) return null;
        c.setPath((String) comment.get("path"));
        c.setDiffHunk((String) comment.get("diff_hunk"));
        c.setLine(comment.get("line") instanceof Number n ? n.intValue() : null);
        c.setPullRequestReviewId(toLong(comment.get("pull_request_review_id")));
        return c;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Issue extractIssue(Map<String, Object> issue) {
        if (issue == null) return null;
        WebhookPayload.Issue i = new WebhookPayload.Issue();
        i.setNumber(toLong(issue.get("number")));
        i.setTitle((String) issue.get("title"));
        i.setBody((String) issue.get("body"));
        i.setUser(extractOwner((Map<String, Object>) issue.get("user")));
        // Native GitHub "issues" payloads do not include "ref". We still map it when present
        // to support translated/custom payloads that supply an explicit issue base branch.
        i.setRef((String) issue.get("ref"));
        if (issue.containsKey("pull_request") && issue.get("pull_request") != null) {
            WebhookPayload.IssuePullRequest ipr = new WebhookPayload.IssuePullRequest();
            Map<String, Object> prLink = (Map<String, Object>) issue.get("pull_request");
            ipr.setMerged(prLink.get("merged_at") != null ? Boolean.TRUE : null);
            i.setPullRequest(ipr);
        }
        Map<String, Object> assignee = (Map<String, Object>) issue.get("assignee");
        if (assignee != null) {
            WebhookPayload.Owner a = new WebhookPayload.Owner();
            a.setLogin((String) assignee.get("login"));
            i.setAssignee(a);
        }
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) issue.get("assignees");
        if (assignees != null) {
            i.setAssignees(assignees.stream().map(am -> {
                WebhookPayload.Owner ao = new WebhookPayload.Owner();
                ao.setLogin((String) am.get("login"));
                return ao;
            }).toList());
        }
        return i;
    }

    private WebhookPayload.Owner extractOwner(Map<String, Object> owner) {
        if (owner == null) return null;
        WebhookPayload.Owner o = new WebhookPayload.Owner();
        o.setLogin((String) owner.get("login"));
        return o;
    }

    private List<WebhookPayload.Owner> extractOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream().map(this::extractOwner).toList();
    }

    private WebhookPayload.Review extractReview(Map<String, Object> review) {
        if (review == null) return null;
        WebhookPayload.Review r = new WebhookPayload.Review();
        r.setId(toLong(review.get("id")));
        r.setType((String) review.get("state"));
        r.setContent((String) review.get("body"));
        return r;
    }

    private String mapAction(String githubAction) {
        if ("synchronize".equals(githubAction)) {
            return "synchronized";
        }
        return githubAction;
    }

    private String mapReviewAction(String githubAction) {
        if ("submitted".equals(githubAction)) {
            return "reviewed";
        }
        return githubAction;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private boolean hasBotReviewer(Bot bot, WebhookPayload payload) {
        return bot.getUsername() != null
                && payload.getPullRequest() != null
                && payload.getPullRequest().getRequestedReviewers() != null
                && payload.getPullRequest().getRequestedReviewers().stream()
                .anyMatch(reviewer -> bot.getUsername().equalsIgnoreCase(reviewer.getLogin()));
    }

    private boolean isRequestedReviewer(Bot bot, WebhookPayload payload) {
        return bot.getUsername() != null
                && payload.getRequestedReviewer() != null
                && bot.getUsername().equalsIgnoreCase(payload.getRequestedReviewer().getLogin());
    }
}
