package org.remus.giteabot.github;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.CommentId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Translates GitHub webhooks into provider-neutral {@link WebhookEvent}s.
 * <p>
 * GitHub event types are delivered via the {@code X-GitHub-Event} header.
 * Supported events: pull_request, issue_comment, pull_request_review,
 * pull_request_review_comment, issues.
 */
@Slf4j
@Component
public class GitHubWebhookTranslator implements WebhookTranslator {

    static final String EVENT_HEADER = "X-GitHub-Event";

    @Override
    public RepositoryType providerType() {
        return RepositoryType.GITHUB;
    }

    /** GitHub signs the body: {@code X-Hub-Signature-256: sha256=<hex hmac>}. */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches("sha256=" + WebhookSignatures.hmacSha256Hex(signingSecret, rawBody),
                headers.get("X-Hub-Signature-256"));
    }

    @Override
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventType = headers.get(EVENT_HEADER);
        if (eventType == null) {
            log.warn("Missing {} header for GitHub webhook", EVENT_HEADER);
            return WebhookEvent.ignored("missing " + EVENT_HEADER + " header");
        }

        log.debug("Processing GitHub event: {} (action={}) for bot '{}'", eventType, payload.get("action"), bot.getName());

        WebhookPayload webhookPayload = translatePayload(eventType, payload);
        if (webhookPayload == null) {
            return WebhookEvent.ignored("unhandled GitHub event type '" + eventType + "'");
        }

        // Every event is about a pull request or an issue; a payload carrying neither is malformed.
        if (webhookPayload.getPullRequest() == null && webhookPayload.getIssue() == null) {
            return WebhookEvent.ignored("GitHub " + eventType + " event without a pull request or issue");
        }

        BotIdentity identity = BotIdentity.of(bot);
        String action = (String) payload.get("action");
        return switch (eventType) {
            case "pull_request" -> classifyPullRequest(identity, action, webhookPayload, payload);
            case "issue_comment" -> classifyIssueComment(identity, action, webhookPayload);
            case "pull_request_review" -> "submitted".equals(action)
                    ? WebhookEvent.of(WebhookEventKind.PR_REVIEW_SUBMITTED, webhookPayload, identity)
                    : WebhookEvent.ignored("pull_request_review action '" + action + "' is not 'submitted'");
            case "pull_request_review_comment" -> "created".equals(action)
                    ? WebhookEvent.of(WebhookEventKind.PR_INLINE_COMMENT, webhookPayload, identity)
                    : WebhookEvent.ignored("review comment action '" + action + "' is not 'created'");
            case "issues" -> classifyIssue(identity, action, webhookPayload, payload);
            default -> WebhookEvent.ignored("unhandled GitHub event type '" + eventType + "'");
        };
    }

    // ---- Classification ----

    @SuppressWarnings("unchecked")
    private WebhookEvent classifyPullRequest(BotIdentity identity, String action, WebhookPayload payload,
                                             Map<String, Object> raw) {
        return switch (action != null ? action : "") {
            case "opened" -> WebhookEvent.of(WebhookEventKind.PR_OPENED, payload, identity);
            case "reopened" -> WebhookEvent.of(WebhookEventKind.PR_REOPENED, payload, identity);
            case "synchronize" -> WebhookEvent.of(WebhookEventKind.PR_SYNCHRONIZED, payload, identity);
            case "closed" -> WebhookEvent.of(WebhookEventKind.PR_CLOSED, payload, identity);
            case "review_requested" -> identity.is(payload.getRequestedReviewer())
                    ? WebhookEvent.of(WebhookEventKind.PR_REVIEWER_ADDED, payload, identity)
                    : WebhookEvent.ignored("review requested from someone other than the bot");
            // GitHub sends one "assigned" event per assignee and names the user just assigned
            // in the top-level "assignee", so someone else being assigned later is ignored.
            case "assigned" -> identity.is(extractOwner((Map<String, Object>) raw.get("assignee")))
                    ? WebhookEvent.of(WebhookEventKind.PR_ASSIGNED, payload, identity)
                    : WebhookEvent.ignored("pull request assigned to someone other than the bot");
            default -> WebhookEvent.ignored("unhandled GitHub pull_request action '" + action + "'");
        };
    }

    /**
     * GitHub delivers pull request conversation comments as {@code issue_comment} events
     * on the pull request's issue; {@code issue.pull_request} tells them apart from
     * comments on plain issues.
     */
    private WebhookEvent classifyIssueComment(BotIdentity identity, String action, WebhookPayload payload) {
        if (!"created".equals(action)) {
            return WebhookEvent.ignored("comment action '" + action + "' is not 'created'");
        }
        if (payload.getIssue() != null && payload.getIssue().getPullRequest() != null) {
            return WebhookEvent.of(WebhookEventKind.PR_COMMENT, payload, identity);
        }
        return WebhookEvent.of(WebhookEventKind.ISSUE_COMMENT, payload, identity);
    }

    private WebhookEvent classifyIssue(BotIdentity identity, String action, WebhookPayload payload,
                                       Map<String, Object> raw) {
        return switch (action != null ? action : "") {
            case "assigned" -> payload.getIssue() != null && botWasAssigned(identity, payload, raw)
                    ? WebhookEvent.of(WebhookEventKind.ISSUE_ASSIGNED, payload, identity)
                    : WebhookEvent.ignored("issue assigned to someone other than the bot");
            case "opened" -> WebhookEvent.of(WebhookEventKind.ISSUE_OPENED, payload, identity);
            case "reopened" -> WebhookEvent.of(WebhookEventKind.ISSUE_REOPENED, payload, identity);
            default -> WebhookEvent.ignored("unhandled GitHub issues action '" + action + "'");
        };
    }

    /**
     * GitHub sends one "assigned" event per assignee, naming the user just assigned in the
     * top-level "assignee". {@code issue.assignee} is only the issue's first assignee: going
     * by it would miss the bot being added second, and re-trigger it whenever someone else is
     * added to an issue it is already first on. It is the fallback for payloads without the
     * top-level field.
     */
    @SuppressWarnings("unchecked")
    private boolean botWasAssigned(BotIdentity identity, WebhookPayload payload, Map<String, Object> raw) {
        WebhookPayload.Owner assigned = extractOwner((Map<String, Object>) raw.get("assignee"));
        return identity.is(assigned != null ? assigned : payload.getIssue().getAssignee());
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
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePullRequestReviewEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
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
        payload.setSender(extractSender(raw));
        payload.setRepository(extractRepository(raw));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setComment(extractReviewComment((Map<String, Object>) raw.get("comment")));
        if (payload.getPullRequest() != null) {
            payload.setNumber(payload.getPullRequest().getNumber());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload translateIssuesEvent(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
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
        pullRequest.setAssignees(extractOwners((List<Map<String, Object>>) pr.get("assignees")));
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
        c.setId(CommentId.NumericCommentId.of(toLong(comment.get("id"))));
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

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
