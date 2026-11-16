package org.remus.giteabot.gitlab;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.BotAccountResolver;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates GitLab webhooks into provider-neutral {@link WebhookEvent}s.
 * <p>
 * GitLab event types are delivered via the {@code X-Gitlab-Event} header.
 * Supported events: Merge Request Hook, Note Hook, Issue Hook.
 * <p>
 * A merge request {@code update} covers pushes, title/description edits, label, reviewer
 * and assignee changes alike. GitLab marks pushes by including
 * {@code object_attributes.oldrev}, and reviewer and assignee changes by listing them
 * under {@code changes} with their previous and current users; those signals are what
 * tell a push, a review request and an assignment apart from an edit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabWebhookTranslator implements WebhookTranslator {

    static final String EVENT_HEADER = "X-Gitlab-Event";

    /** The event types {@link #translate} classifies; every other one is ignored. */
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of("Merge Request Hook", "Note Hook", "Issue Hook");

    private final BotAccountResolver botAccountResolver;

    @Override
    public RepositoryType providerType() {
        return RepositoryType.GITLAB;
    }

    /** GitLab sends the shared secret itself: {@code X-Gitlab-Token: <secret>}. */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches(signingSecret, headers.get("X-Gitlab-Token"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventType = headers.get(EVENT_HEADER);
        if (eventType == null) {
            log.warn("Missing {} header for GitLab webhook", EVENT_HEADER);
            return WebhookEvent.ignored("missing " + EVENT_HEADER + " header");
        }

        log.debug("Processing GitLab event: {} for bot '{}'", eventType, bot.getName());

        // Checked before the bot's account is resolved: that may ask GitLab, and a failure is
        // recorded on the bot, which pipeline, job and push events must not cause.
        if (!HANDLED_EVENT_TYPES.contains(eventType)) {
            return WebhookEvent.ignored("unhandled GitLab event type '" + eventType + "'");
        }

        Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
        if (attrs == null) {
            return WebhookEvent.ignored("GitLab " + eventType + " without object_attributes");
        }

        RepositoryUser botAccount = botAccountResolver.resolve(bot, null);
        if (botAccount == null) {
            return WebhookEvent.ignored("bot account could not be resolved");
        }
        BotIdentity identity = new BotIdentity(botAccount);
        return switch (eventType) {
            case "Merge Request Hook" -> classifyMergeRequest(bot, identity, payload, attrs);
            case "Note Hook" -> classifyNote(identity, payload, attrs);
            case "Issue Hook" -> classifyIssue(identity, payload, attrs);
            // Required for exhaustiveness only: HANDLED_EVENT_TYPES let nothing else through.
            default -> WebhookEvent.ignored("unhandled GitLab event type '" + eventType + "'");
        };
    }

    // ---- Classification ----

    @SuppressWarnings("unchecked")
    private WebhookEvent classifyMergeRequest(Bot bot, BotIdentity identity, Map<String, Object> payload,
                                              Map<String, Object> attrs) {
        String gitlabAction = (String) attrs.get("action");
        WebhookPayload webhookPayload = translateMergeRequestPayload(payload, attrs);

        return switch (gitlabAction != null ? gitlabAction : "") {
            case "open" -> WebhookEvent.of(WebhookEventKind.PR_OPENED, webhookPayload, identity);
            case "reopen" -> WebhookEvent.of(WebhookEventKind.PR_REOPENED, webhookPayload, identity);
            case "update" -> {
                Map<String, Object> changes = (Map<String, Object>) payload.get("changes");
                if (botWasAdded(identity, changes, "reviewers")) {
                    yield WebhookEvent.of(WebhookEventKind.PR_REVIEWER_ADDED, webhookPayload, identity);
                }
                if (botWasAdded(identity, changes, "assignees")) {
                    yield WebhookEvent.of(WebhookEventKind.PR_ASSIGNED, webhookPayload, identity);
                }
                if (isPush(attrs)) {
                    yield WebhookEvent.of(WebhookEventKind.PR_SYNCHRONIZED, webhookPayload, identity);
                }
                yield WebhookEvent.ignored(
                        "merge request update without a push or the bot being added as reviewer or assignee");
            }
            case "close", "merge" -> WebhookEvent.of(WebhookEventKind.PR_CLOSED, webhookPayload, identity);
            case "approved" -> {
                // An "approved" event attributed to the bot while the bot is a reviewer is
                // treated as GitLab re-requesting a review from it — the one bot-authored
                // event the bot acts on. When the bot's own post-review action is APPROVE,
                // the event is just that approval echoing back.
                if (identity.triggered(webhookPayload)
                        && identity.isReviewer(webhookPayload)
                        && !isApprovePostReviewActionConfigured(bot)) {
                    yield WebhookEvent.builder()
                            .kind(WebhookEventKind.PR_REVIEWER_ADDED)
                            .payload(webhookPayload)
                            .fromBot(false)
                            .botIsReviewer(true)
                            .botAlias(identity.alias())
                            .build();
                }
                yield WebhookEvent.ignored("merge request approval");
            }
            default -> WebhookEvent.ignored("unhandled GitLab merge request action '" + gitlabAction + "'");
        };
    }

    /**
     * Note Hook events are comments on merge requests, issues, commits and snippets; only the
     * first two are handled. Notes carrying a diff {@code position} are inline comments.
     */
    @SuppressWarnings("unchecked")
    private WebhookEvent classifyNote(BotIdentity identity, Map<String, Object> payload, Map<String, Object> attrs) {
        String noteableType = (String) attrs.get("noteable_type");
        if (!"MergeRequest".equals(noteableType) && !"Issue".equals(noteableType)) {
            return WebhookEvent.ignored("note on unsupported noteable type '" + noteableType + "'");
        }

        WebhookPayload webhookPayload = translateNotePayload(payload, attrs);

        if ("Issue".equals(noteableType)) {
            return WebhookEvent.of(WebhookEventKind.ISSUE_COMMENT, webhookPayload, identity);
        }
        if (webhookPayload.getPullRequest() == null) {
            return WebhookEvent.ignored("merge request note without merge_request object");
        }

        Map<String, Object> position = (Map<String, Object>) attrs.get("position");
        String path = position != null ? (String) position.get("new_path") : null;
        if (path != null && !path.isBlank()) {
            webhookPayload.getComment().setPath(path);
            if (position.get("new_line") instanceof Number n) {
                webhookPayload.getComment().setLine(n.intValue());
            }
            return WebhookEvent.of(WebhookEventKind.PR_INLINE_COMMENT, webhookPayload, identity);
        }

        return WebhookEvent.of(WebhookEventKind.PR_COMMENT, webhookPayload, identity);
    }

    @SuppressWarnings("unchecked")
    private WebhookEvent classifyIssue(BotIdentity identity, Map<String, Object> payload, Map<String, Object> attrs) {
        String gitlabAction = (String) attrs.get("action");
        if ("open".equals(gitlabAction) || "reopen".equals(gitlabAction)) {
            WebhookPayload webhookPayload = translateIssuePayload(payload, attrs);
            boolean opened = "open".equals(gitlabAction);
            return WebhookEvent.of(opened ? WebhookEventKind.ISSUE_OPENED : WebhookEventKind.ISSUE_REOPENED, webhookPayload, identity);
        }
        if (!"update".equals(gitlabAction)) {
            return WebhookEvent.ignored("unhandled GitLab issue action '" + gitlabAction + "'");
        }

        Map<String, Object> changes = (Map<String, Object>) payload.get("changes");
        if (!botWasAdded(identity, changes, "assignees")) {
            return WebhookEvent.ignored("issue update without the bot being newly assigned");
        }
        WebhookPayload webhookPayload = translateIssuePayload(payload, attrs);
        return WebhookEvent.of(WebhookEventKind.ISSUE_ASSIGNED, webhookPayload, identity);
    }

    /**
     * GitLab includes {@code object_attributes.oldrev} on a merge request update only when
     * new commits were pushed to the source branch.
     */
    private static boolean isPush(Map<String, Object> attrs) {
        return attrs.get("oldrev") instanceof String oldrev && !oldrev.isBlank();
    }

    /**
     * Whether the bot appears in {@code changes.<field>.current} but not in
     * {@code changes.<field>.previous}, i.e. it was just added rather than already present.
     */
    @SuppressWarnings("unchecked")
    private boolean botWasAdded(BotIdentity identity, Map<String, Object> changes, String field) {
        if (changes == null || !(changes.get(field) instanceof Map<?, ?> change)) {
            return false;
        }
        return containsBot(identity, (List<Map<String, Object>>) change.get("current"))
                && !containsBot(identity, (List<Map<String, Object>>) change.get("previous"));
    }

    private boolean containsBot(BotIdentity identity, List<Map<String, Object>> users) {
        List<WebhookPayload.Owner> owners = extractGitLabOwners(users);
        return owners != null && owners.stream().anyMatch(identity::is);
    }

    // ---- GitLab → WebhookPayload translation ----

    /**
     * Translates a GitLab merge request webhook payload to the common WebhookPayload format.
     */
    @SuppressWarnings("unchecked")
    WebhookPayload translateMergeRequestPayload(Map<String, Object> gitlabPayload,
                                                Map<String, Object> attrs) {
        WebhookPayload payload = new WebhookPayload();

        // Pull request
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setId(toLong(attrs.get("id")));
        pr.setNumber(toLong(attrs.get("iid")));
        pr.setTitle((String) attrs.get("title"));
        pr.setBody((String) attrs.get("description"));
        pr.setState(mapMrState((String) attrs.get("state")));
        pr.setUser(extractGitLabOwner((Map<String, Object>) gitlabPayload.get("object_attributes"), "author"));
        if (pr.getUser() == null) {
            pr.setUser(extractGitLabOwner((Map<String, Object>) gitlabPayload.get("merge_request"), "author"));
        }
        pr.setRequestedReviewers(extractGitLabOwners((List<Map<String, Object>>) gitlabPayload.get("reviewers")));
        pr.setAssignees(extractGitLabOwners((List<Map<String, Object>>) gitlabPayload.get("assignees")));

        // Head and base
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef((String) attrs.get("source_branch"));
        Map<String, Object> lastCommit = (Map<String, Object>) attrs.get("last_commit");
        if (lastCommit != null) {
            head.setSha((String) lastCommit.get("id"));
        }
        pr.setHead(head);

        WebhookPayload.Head base = new WebhookPayload.Head();
        base.setRef((String) attrs.get("target_branch"));
        pr.setBase(base);

        payload.setPullRequest(pr);
        payload.setNumber(pr.getNumber());

        // Repository
        Map<String, Object> project = (Map<String, Object>) gitlabPayload.get("project");
        if (project != null) {
            payload.setRepository(translateRepository(project));
        }

        // Sender
        Map<String, Object> user = (Map<String, Object>) gitlabPayload.get("user");
        if (user != null) {
            payload.setSender(gitLabUser(user));
        }

        return payload;
    }

    /**
     * Translates a GitLab issue webhook payload to the common WebhookPayload format.
     */
    @SuppressWarnings("unchecked")
    WebhookPayload translateIssuePayload(Map<String, Object> gitlabPayload,
                                         Map<String, Object> attrs) {
        WebhookPayload payload = new WebhookPayload();

        // Issue
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(toLong(attrs.get("iid")));
        issue.setTitle((String) attrs.get("title"));
        issue.setBody((String) attrs.get("description"));

        // Set the first assignee (GitLab uses assignee_id in object_attributes)
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) gitlabPayload.get("assignees");
        if (assignees != null && !assignees.isEmpty()) {
            issue.setAssignee(gitLabUser(assignees.getFirst()));
            issue.setAssignees(extractGitLabOwners(assignees));
        }

        payload.setIssue(issue);

        // Repository
        Map<String, Object> project = (Map<String, Object>) gitlabPayload.get("project");
        if (project != null) {
            payload.setRepository(translateRepository(project));
        }

        // Sender
        Map<String, Object> user = (Map<String, Object>) gitlabPayload.get("user");
        if (user != null) {
            payload.setSender(gitLabUser(user));
        }

        return payload;
    }

    /**
     * Translates a GitLab note (comment) webhook payload to the common WebhookPayload format.
     */
    @SuppressWarnings("unchecked")
    WebhookPayload translateNotePayload(Map<String, Object> gitlabPayload,
                                        Map<String, Object> noteAttrs) {
        WebhookPayload payload = new WebhookPayload();

        // Comment
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(CommentId.NumericCommentId.of(toLong(noteAttrs.get("id"))));
        comment.setBody((String) noteAttrs.get("note"));
        Map<String, Object> author = (Map<String, Object>) noteAttrs.get("author");
        Map<String, Object> user = (Map<String, Object>) gitlabPayload.get("user");
        WebhookPayload.Owner commentUser = toGitLabOwner(author);
        if (commentUser == null) {
            commentUser = toGitLabOwner(user);
        }
        if (commentUser != null) {
            comment.setUser(commentUser);
        }
        payload.setComment(comment);

        // Repository
        Map<String, Object> project = (Map<String, Object>) gitlabPayload.get("project");
        if (project != null) {
            payload.setRepository(translateRepository(project));
        }

        // Sender
        if (user != null) {
            WebhookPayload.Owner sender = toGitLabOwner(user);
            payload.setSender(sender);
        }

        // Populate issue/MR context from the noteable object
        String noteableType = (String) noteAttrs.get("noteable_type");
        if ("MergeRequest".equals(noteableType)) {
            Map<String, Object> mr = (Map<String, Object>) gitlabPayload.get("merge_request");
            if (mr != null) {
                WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
                pr.setId(toLong(mr.get("id")));
                pr.setNumber(toLong(mr.get("iid")));
                pr.setTitle((String) mr.get("title"));
                pr.setBody((String) mr.get("description"));
                pr.setUser(extractGitLabAuthor(mr, user));
                WebhookPayload.Head head = new WebhookPayload.Head();
                head.setRef((String) mr.get("source_branch"));
                Map<String, Object> lastCommit = (Map<String, Object>) mr.get("last_commit");
                if (lastCommit != null) {
                    head.setSha((String) lastCommit.get("id"));
                }
                pr.setHead(head);
                WebhookPayload.Head base = new WebhookPayload.Head();
                base.setRef((String) mr.get("target_branch"));
                pr.setBase(base);
                payload.setPullRequest(pr);
            }
        } else if ("Issue".equals(noteableType)) {
            Map<String, Object> issue = (Map<String, Object>) gitlabPayload.get("issue");
            if (issue != null) {
                WebhookPayload.Issue webhookIssue = new WebhookPayload.Issue();
                webhookIssue.setNumber(toLong(issue.get("iid")));
                webhookIssue.setTitle((String) issue.get("title"));
                webhookIssue.setBody((String) issue.get("description"));
                payload.setIssue(webhookIssue);
            }
        }

        return payload;
    }

    private WebhookPayload.Repository translateRepository(Map<String, Object> project) {
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setId(toLong(project.get("id")));
        repo.setName((String) project.get("name"));

        // GitLab uses path_with_namespace (e.g., "owner/repo")
        String pathWithNamespace = (String) project.get("path_with_namespace");
        repo.setFullName(pathWithNamespace);

        // Namespace and project names can be display labels; prefer the canonical path.
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        if (pathWithNamespace != null && pathWithNamespace.contains("/")) {
            int separator = pathWithNamespace.lastIndexOf('/');
            owner.setLogin(pathWithNamespace.substring(0, separator));
            repo.setName(pathWithNamespace.substring(separator + 1));
        } else {
            // Retain existing namespace payload support when the canonical path is absent.
            Object namespace = project.get("namespace");
            if (namespace instanceof String ns) {
                owner.setLogin(ns);
            } else if (namespace instanceof Map<?, ?> nsMap) {
                String path = (String) nsMap.get("path");
                if (path != null) {
                    owner.setLogin(path);
                }
            }
        }
        repo.setOwner(owner);

        return repo;
    }

    private String mapMrState(String gitlabState) {
        if (gitlabState == null) return null;
        return switch (gitlabState) {
            case "opened" -> "open";
            case "closed", "merged" -> "closed";
            default -> gitlabState;
        };
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private boolean isApprovePostReviewActionConfigured(Bot bot) {
        return bot.getGitIntegration() != null
                && bot.getGitIntegration().getPostReviewAction() == PostReviewAction.APPROVE;
    }


    @SuppressWarnings("unchecked")
    private WebhookPayload.Owner extractGitLabOwner(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map<?, ?> ownerMap)) {
            return null;
        }
        return toGitLabOwner((Map<String, Object>) ownerMap);
    }

    private WebhookPayload.Owner extractGitLabAuthor(Map<String, Object> source, Map<String, Object> fallbackUser) {
        WebhookPayload.Owner owner = extractGitLabOwner(source, "author");
        if (owner != null) {
            return owner;
        }
        if (source == null || fallbackUser == null) {
            return null;
        }
        Long authorId = toLong(source.get("author_id"));
        Long userId = toLong(fallbackUser.get("id"));
        if (authorId != null && authorId.equals(userId)) {
            return toGitLabOwner(fallbackUser);
        }
        return null;
    }

    private WebhookPayload.Owner toGitLabOwner(Map<String, Object> user) {
        if (user == null || user.get("username") == null) {
            return null;
        }
        return gitLabUser(user);
    }

    private List<WebhookPayload.Owner> extractGitLabOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream()
                .map(this::gitLabUser)
                .toList();
    }

    /** A GitLab user object: its numeric {@code id} and its {@code username} as login. */
    private WebhookPayload.Owner gitLabUser(Map<String, Object> user) {
        return WebhookPayload.Owner.of(user.get("id"), (String) user.get("username"));
    }

}
