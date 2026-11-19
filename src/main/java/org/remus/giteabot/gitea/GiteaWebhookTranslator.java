package org.remus.giteabot.gitea;

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
 * Translates Gitea webhooks into provider-neutral {@link WebhookEvent}s.
 * <p>
 * Gitea's field names closely match {@link WebhookPayload}, so the payload translation
 * is mostly a one-to-one copy. The event type comes from the {@code X-Gitea-Event} header,
 * which carries Gitea's coarse event name ({@code HookEventType.Event()} in Gitea's
 * source): several fine-grained types share one name, e.g. {@code issue_assign} arrives
 * as {@code issues}, and comments on issues and pull requests alike as
 * {@code issue_comment}.
 */
@Slf4j
@Component
public class GiteaWebhookTranslator implements WebhookTranslator {

    static final String EVENT_HEADER = "X-Gitea-Event";

    /**
     * Forgejo's own name for the same header. Every Forgejo release sends both with the same
     * value; this one is read only when {@link #EVENT_HEADER} is absent.
     */
    static final String FORGEJO_EVENT_HEADER = "X-Forgejo-Event";

    @Override
    public RepositoryType providerType() {
        return RepositoryType.GITEA;
    }

    /** Gitea signs the body: {@code X-Gitea-Signature: <hex hmac>}. */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches(WebhookSignatures.hmacSha256Hex(signingSecret, rawBody),
                headers.get("X-Gitea-Signature"));
    }

    @Override
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventType = eventType(headers);
        if (eventType == null) {
            // Every Gitea (1.12+) and Forgejo release sends the header, so its absence points
            // at a proxy or replay tool that drops custom headers.
            log.warn("Missing {} / {} header for Gitea webhook to bot '{}' — check proxies in between",
                    EVENT_HEADER, FORGEJO_EVENT_HEADER, bot.getName());
            return WebhookEvent.ignored("missing " + EVENT_HEADER + " header");
        }

        WebhookPayload webhookPayload = translatePayload(payload);

        log.debug("Gitea {} event: action={}, pullRequest={}, comment={}, issue={}, sender={}",
                eventType,
                payload.get("action"),
                webhookPayload.getPullRequest() != null ? "present" : "null",
                webhookPayload.getComment() != null ? "present" : "null",
                webhookPayload.getIssue() != null ? "present" : "null",
                webhookPayload.getSender() != null ? webhookPayload.getSender().getLogin() : "null");

        BotIdentity identity = BotIdentity.of(bot);
        String action = (String) payload.get("action");
        return switch (eventType) {
            case "issue_comment" -> classifyComment(identity, action, webhookPayload);
            case "issues" -> classifyIssue(identity, action, webhookPayload);
            case "pull_request" -> classifyPullRequest(identity, action, webhookPayload);
            // Submitted reviews: one event name per review type, all with action "reviewed".
            case "pull_request_approved", "pull_request_rejected", "pull_request_comment" ->
                    classifyReview(identity, action, webhookPayload);
            default -> {
                log.info("Ignoring Gitea webhook for bot '{}': unhandled event type '{}'", bot.getName(), eventType);
                yield WebhookEvent.ignored("unhandled Gitea event type '" + eventType + "'");
            }
        };
    }

    /** The event type from {@link #EVENT_HEADER}, else {@link #FORGEJO_EVENT_HEADER}; blank counts as absent. */
    private static String eventType(Map<String, String> headers) {
        String gitea = headers.get(EVENT_HEADER);
        if (gitea != null && !gitea.isBlank()) {
            return gitea.trim();
        }
        String forgejo = headers.get(FORGEJO_EVENT_HEADER);
        return forgejo != null && !forgejo.isBlank() ? forgejo.trim() : null;
    }

    // ---- Classification ----

    /**
     * Gitea sends every comment — on an issue or a pull request, top-level or inline — as
     * {@code issue_comment}. Inline comments are the ones carrying a file path; a pull
     * request is signalled by a top-level {@code pull_request} or, on a plain
     * conversation comment, only by the nested {@code issue.pull_request}.
     */
    private WebhookEvent classifyComment(BotIdentity identity, String action, WebhookPayload payload) {
        if (!"created".equals(action)) {
            return WebhookEvent.ignored("comment action '" + action + "' is not 'created'");
        }
        WebhookPayload.Comment comment = payload.getComment();
        if (comment == null) {
            return WebhookEvent.ignored("Gitea comment event without a comment");
        }
        if (comment.getPath() != null && !comment.getPath().isBlank()) {
            return WebhookEvent.of(WebhookEventKind.PR_INLINE_COMMENT, payload, identity);
        }
        if (payload.getIssue() == null) {
            return WebhookEvent.ignored("Gitea comment event without an issue");
        }
        boolean isPrComment = payload.getPullRequest() != null
                || payload.getIssue().getPullRequest() != null;
        if (isPrComment) {
            return WebhookEvent.of(WebhookEventKind.PR_COMMENT, payload, identity);
        }
        return WebhookEvent.of(WebhookEventKind.ISSUE_COMMENT, payload, identity);
    }

    private WebhookEvent classifyIssue(BotIdentity identity, String action, WebhookPayload payload) {
        if (payload.getIssue() == null) {
            return WebhookEvent.ignored("Gitea issues event without an issue");
        }
        return switch (action != null ? action : "") {
            // Gitea names no assignee on the event itself, and issue.assignee is only the
            // first of issue.assignees, so the bot is looked for among all of them.
            case "assigned" -> identity.isIssueAssignee(payload)
                    ? WebhookEvent.of(WebhookEventKind.ISSUE_ASSIGNED, payload, identity)
                    : WebhookEvent.ignored("issue assigned to someone other than the bot");
            case "opened" -> WebhookEvent.of(WebhookEventKind.ISSUE_OPENED, payload, identity);
            case "reopened" -> WebhookEvent.of(WebhookEventKind.ISSUE_REOPENED, payload, identity);
            default -> WebhookEvent.ignored("unhandled Gitea issues action '" + action + "'");
        };
    }

    private WebhookEvent classifyPullRequest(BotIdentity identity, String action, WebhookPayload payload) {
        if (payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Gitea pull_request event without a pull request");
        }
        return switch (action != null ? action : "") {
            case "opened" -> WebhookEvent.of(WebhookEventKind.PR_OPENED, payload, identity);
            case "reopened" -> WebhookEvent.of(WebhookEventKind.PR_REOPENED, payload, identity);
            case "synchronized" -> WebhookEvent.of(WebhookEventKind.PR_SYNCHRONIZED, payload, identity);
            case "closed" -> WebhookEvent.of(WebhookEventKind.PR_CLOSED, payload, identity);
            // As for issues, the event names no assignee, so the bot is looked for among all
            // of the pull request's assignees.
            case "assigned" -> identity.isPullRequestAssignee(payload)
                    ? WebhookEvent.of(WebhookEventKind.PR_ASSIGNED, payload, identity)
                    : WebhookEvent.ignored("pull request assigned to someone other than the bot");
            case "review_requested" -> identity.is(payload.getRequestedReviewer())
                    ? WebhookEvent.of(WebhookEventKind.PR_REVIEWER_ADDED, payload, identity)
                    : WebhookEvent.ignored("review requested from someone other than the bot");
            default -> WebhookEvent.ignored("unhandled Gitea pull_request action '" + action + "'");
        };
    }

    private WebhookEvent classifyReview(BotIdentity identity, String action, WebhookPayload payload) {
        if (!"reviewed".equals(action) || payload.getReview() == null
                || payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Gitea review event without a submitted review");
        }
        return WebhookEvent.of(WebhookEventKind.PR_REVIEW_SUBMITTED, payload, identity);
    }

    // ---- Gitea → WebhookPayload translation ----

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePayload(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setNumber(toLong(raw.get("number")));
        payload.setSender(extractOwner((Map<String, Object>) raw.get("sender")));
        payload.setRepository(extractRepository((Map<String, Object>) raw.get("repository")));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        payload.setReview(extractReview((Map<String, Object>) raw.get("review")));
        payload.setRequestedReviewer(extractOwner((Map<String, Object>) raw.get("requested_reviewer")));
        return payload;
    }

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
        p.setUser(extractOwner((Map<String, Object>) pr.get("user")));
        p.setAssignee(extractOwner((Map<String, Object>) pr.get("assignee")));
        p.setAssignees(extractOwners((List<Map<String, Object>>) pr.get("assignees")));
        p.setRequestedReviewers(extractOwners((List<Map<String, Object>>) pr.get("requested_reviewers")));

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
        i.setUser(extractOwner((Map<String, Object>) issue.get("user")));
        i.setAssignee(extractOwner((Map<String, Object>) issue.get("assignee")));

        // Check for pull_request link
        if (issue.get("pull_request") != null) {
            WebhookPayload.IssuePullRequest ipr = new WebhookPayload.IssuePullRequest();
            Map<String, Object> prLink = (Map<String, Object>) issue.get("pull_request");
            ipr.setMerged(prLink.get("merged") instanceof Boolean b ? b : null);
            i.setPullRequest(ipr);
        }

        i.setAssignees(extractOwners((List<Map<String, Object>>) issue.get("assignees")));
        return i;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(CommentId.NumericCommentId.of(toLong(comment.get("id"))));
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

    private List<WebhookPayload.Owner> extractOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream().map(this::extractOwner).toList();
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
