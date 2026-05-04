package org.remus.giteabot.gitlab;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for GitLab webhook events.
 * <p>
 * Receives GitLab webhook payloads and translates them into the common
 * {@link WebhookPayload} model used by the rest of the application, then
 * delegates to {@link BotWebhookService} for actual processing.
 * <p>
 * GitLab event types are delivered via the {@code X-Gitlab-Event} header.
 * Supported events: Merge Request Hook, Note Hook, Issue Hook.
 */
@Slf4j
@Component
public class GitLabWebhookHandler {

    private final BotWebhookService botWebhookService;

    public GitLabWebhookHandler(BotWebhookService botWebhookService) {
        this.botWebhookService = botWebhookService;
    }

    /**
     * Handles a GitLab webhook event for the given bot.
     *
     * @param bot       the bot to process the webhook for
     * @param eventType the GitLab event type from X-Gitlab-Event header
     * @param payload   the raw webhook payload
     * @return response indicating the result of webhook processing
     */
    public ResponseEntity<String> handleWebhook(Bot bot, String eventType, Map<String, Object> payload) {
        if (eventType == null) {
            log.warn("Missing X-Gitlab-Event header for GitLab webhook");
            return ResponseEntity.ok("ignored");
        }

        log.debug("Processing GitLab event: {} for bot '{}'", eventType, bot.getName());

        return switch (eventType) {
            case "Merge Request Hook" -> handleMergeRequestEvent(bot, payload);
            case "Note Hook" -> handleNoteEvent(bot, payload);
            case "Issue Hook" -> handleIssueEvent(bot, payload);
            default -> {
                log.debug("Unhandled GitLab event type: {}", eventType);
                yield ResponseEntity.ok("ignored");
            }
        };
    }

    // ---- Event handlers ----

    /**
     * Handles GitLab Merge Request Hook events.
     * Maps to PR opened/synchronized/closed events.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<String> handleMergeRequestEvent(Bot bot, Map<String, Object> payload) {
        Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
        if (attrs == null) {
            return ResponseEntity.ok("ignored");
        }

        String gitlabAction = (String) attrs.get("action");
        WebhookPayload webhookPayload = translateMergeRequestPayload(payload, attrs);

        boolean botUserEvent = botWebhookService.isBotUser(bot, webhookPayload);
        boolean botReviewerReRequestEvent = isBotReviewerReRequestEvent(bot, payload, gitlabAction);

        // Ignore events from the bot itself, except GitLab reviewer re-request events.
        if (botUserEvent && !botReviewerReRequestEvent) {
            log.debug("Ignoring GitLab event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        return switch (gitlabAction != null ? gitlabAction : "") {
            case "open" -> {
                webhookPayload.setAction("opened");
                if (hasBotReviewer(bot, payload, null)) {
                    botWebhookService.reviewPullRequest(bot, webhookPayload);
                    yield ResponseEntity.ok("review triggered");
                }
                yield ResponseEntity.ok("ignored");
            }
            case "update" -> {
                webhookPayload.setAction("synchronized");
                Map<String, Object> changes = (Map<String, Object>) payload.get("changes");
                if (changes != null && hasBotReviewer(bot, payload, changes)) {
                    botWebhookService.reviewPullRequest(bot, webhookPayload);
                    yield ResponseEntity.ok("review triggered");
                }
                yield ResponseEntity.ok("ignored");
            }
            case "close", "merge" -> {
                webhookPayload.setAction("closed");
                botWebhookService.handlePrClosed(bot, webhookPayload);
                yield ResponseEntity.ok("session closed");
            }
            case "approved" -> {
                if (botUserEvent && botReviewerReRequestEvent) {
                    webhookPayload.setAction("review_requested");
                    botWebhookService.reviewPullRequest(bot, webhookPayload);
                    yield ResponseEntity.ok("review triggered");
                }
                yield ResponseEntity.ok("ignored");
            }
            default -> ResponseEntity.ok("ignored");
        };
    }

    /**
     * Handles GitLab Note Hook events (comments on MRs and issues).
     * Maps to PR comment or issue comment events.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<String> handleNoteEvent(Bot bot, Map<String, Object> payload) {
        Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
        if (attrs == null) {
            return ResponseEntity.ok("ignored");
        }

        String noteableType = (String) attrs.get("noteable_type");
        String noteBody = (String) attrs.get("note");
        String botAlias = botWebhookService.getBotAlias(bot);

        if (noteBody == null || !noteBody.contains(botAlias)) {
            return ResponseEntity.ok("ignored");
        }

        if ("MergeRequest".equals(noteableType)) {
            return handleMergeRequestNote(bot, payload, attrs);
        } else if ("Issue".equals(noteableType)) {
            return handleIssueNote(bot, payload, attrs);
        }

        return ResponseEntity.ok("ignored");
    }

    /**
     * Handles a comment on a GitLab merge request.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<String> handleMergeRequestNote(Bot bot, Map<String, Object> payload,
                                                           Map<String, Object> noteAttrs) {
        WebhookPayload webhookPayload = translateNotePayload(payload, noteAttrs);
        webhookPayload.setAction("created");

        // Ignore events from the bot itself
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            return ResponseEntity.ok("ignored");
        }

        // Check if this is an inline comment (has position info)
        Map<String, Object> position = (Map<String, Object>) noteAttrs.get("position");
        if (position != null) {
            String path = (String) position.get("new_path");
            if (path != null && !path.isBlank()) {
                webhookPayload.getComment().setPath(path);
                Object newLine = position.get("new_line");
                if (newLine instanceof Number n) {
                    webhookPayload.getComment().setLine(n.intValue());
                }
                botWebhookService.handleInlineComment(bot, webhookPayload);
                return ResponseEntity.ok("inline comment response triggered");
            }
        }

        // Mark as a PR comment (set a dummy pull_request on the issue)
        if (webhookPayload.getIssue() != null) {
            WebhookPayload.IssuePullRequest issuePr = new WebhookPayload.IssuePullRequest();
            issuePr.setMerged(false);
            webhookPayload.getIssue().setPullRequest(issuePr);
        }

        String botAlias = botWebhookService.getBotAlias(bot);
        if (botWebhookService.isReviewAgainRequest(webhookPayload, botAlias)) {
            if (botWebhookService.isReviewAgainRequestFromPullRequestAuthor(webhookPayload, botAlias)) {
                botWebhookService.reviewPullRequest(bot, webhookPayload);
                return ResponseEntity.ok("review triggered");
            }
            return ResponseEntity.ok("ignored");
        }

        botWebhookService.handleBotCommand(bot, webhookPayload);
        return ResponseEntity.ok("command received");
    }

    /**
     * Handles a comment on a GitLab issue (non-MR).
     */
    private ResponseEntity<String> handleIssueNote(Bot bot, Map<String, Object> payload,
                                                    Map<String, Object> noteAttrs) {
        WebhookPayload webhookPayload = translateNotePayload(payload, noteAttrs);
        webhookPayload.setAction("created");

        // Ignore events from the bot itself
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            return ResponseEntity.ok("ignored");
        }

        botWebhookService.handleIssueComment(bot, webhookPayload);
        return ResponseEntity.ok("issue comment received");
    }

    /**
     * Handles GitLab Issue Hook events.
     * Detects when the bot is assigned to an issue and triggers the agent.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<String> handleIssueEvent(Bot bot, Map<String, Object> payload) {
        Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
        if (attrs == null) {
            return ResponseEntity.ok("ignored");
        }

        String gitlabAction = (String) attrs.get("action");

        // We're interested in "update" actions where assignees changed
        if (!"update".equals(gitlabAction)) {
            log.debug("Ignoring GitLab issue action: {}", gitlabAction);
            return ResponseEntity.ok("ignored");
        }

        // Check if assignees were changed
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes");
        if (changes == null || !changes.containsKey("assignees")) {
            log.debug("No assignee changes in GitLab issue update, ignoring");
            return ResponseEntity.ok("ignored");
        }

        // Check if the bot was newly assigned
        Map<String, Object> assigneeChanges = (Map<String, Object>) changes.get("assignees");
        List<Map<String, Object>> currentAssignees = assigneeChanges != null
                ? (List<Map<String, Object>>) assigneeChanges.get("current") : null;
        List<Map<String, Object>> previousAssignees = assigneeChanges != null
                ? (List<Map<String, Object>>) assigneeChanges.get("previous") : null;

        if (!isBotNewlyAssigned(bot, currentAssignees, previousAssignees)) {
            log.debug("Bot '{}' was not newly assigned to the issue, ignoring", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        WebhookPayload webhookPayload = translateIssuePayload(payload, attrs);
        webhookPayload.setAction("assigned");

        // Ignore events from the bot itself
        if (botWebhookService.isBotUser(bot, webhookPayload)) {
            log.debug("Ignoring GitLab issue event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        botWebhookService.handleIssueAssigned(bot, webhookPayload);
        return ResponseEntity.ok("agent triggered");
    }

    /**
     * Checks whether the bot's username appears in the current assignees but not in the previous ones.
     */
    private boolean isBotNewlyAssigned(Bot bot, List<Map<String, Object>> currentAssignees,
                                        List<Map<String, Object>> previousAssignees) {
        if (bot.getUsername() == null || currentAssignees == null) {
            return false;
        }
        boolean inCurrent = currentAssignees.stream()
                .anyMatch(a -> bot.getUsername().equalsIgnoreCase((String) a.get("username")));
        boolean inPrevious = previousAssignees != null && previousAssignees.stream()
                .anyMatch(a -> bot.getUsername().equalsIgnoreCase((String) a.get("username")));
        return inCurrent && !inPrevious;
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
            WebhookPayload.Owner sender = new WebhookPayload.Owner();
            sender.setLogin((String) user.get("username"));
            payload.setSender(sender);
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
            Map<String, Object> firstAssignee = assignees.getFirst();
            WebhookPayload.Owner assignee = new WebhookPayload.Owner();
            assignee.setLogin((String) firstAssignee.get("username"));
            issue.setAssignee(assignee);

            // Map all assignees
            List<WebhookPayload.Owner> allAssignees = new ArrayList<>();
            for (Map<String, Object> a : assignees) {
                WebhookPayload.Owner ao = new WebhookPayload.Owner();
                ao.setLogin((String) a.get("username"));
                allAssignees.add(ao);
            }
            issue.setAssignees(allAssignees);
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
            WebhookPayload.Owner sender = new WebhookPayload.Owner();
            sender.setLogin((String) user.get("username"));
            payload.setSender(sender);
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
        comment.setId(toLong(noteAttrs.get("id")));
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
                // Set issue with MR number (needed for bot command routing)
                WebhookPayload.Issue issue = new WebhookPayload.Issue();
                issue.setNumber(toLong(mr.get("iid")));
                issue.setTitle((String) mr.get("title"));
                issue.setBody((String) mr.get("description"));
                issue.setUser(extractGitLabAuthor(mr, user));
                payload.setIssue(issue);

                // Also set pullRequest for context
                WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
                pr.setId(toLong(mr.get("id")));
                pr.setNumber(toLong(mr.get("iid")));
                pr.setTitle((String) mr.get("title"));
                pr.setBody((String) mr.get("description"));
                pr.setUser(extractGitLabAuthor(mr, user));
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

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository translateRepository(Map<String, Object> project) {
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setId(toLong(project.get("id")));
        repo.setName((String) project.get("name"));

        // GitLab uses path_with_namespace (e.g., "owner/repo")
        String pathWithNamespace = (String) project.get("path_with_namespace");
        repo.setFullName(pathWithNamespace);

        // Extract owner from the namespace
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        if (pathWithNamespace != null && pathWithNamespace.contains("/")) {
            owner.setLogin(pathWithNamespace.substring(0, pathWithNamespace.lastIndexOf('/')));
        }
        // GitLab sends namespace as a String (e.g. Issue Hook) or as an object with "path" (e.g. MR Hook)
        Object namespace = project.get("namespace");
        if (namespace instanceof String ns) {
            owner.setLogin(ns);
        } else if (namespace instanceof Map<?, ?> nsMap) {
            String path = (String) nsMap.get("path");
            if (path != null) {
                owner.setLogin(path);
            }
        }
        repo.setOwner(owner);

        return repo;
    }

    private String mapMrState(String gitlabState) {
        if (gitlabState == null) return null;
        return switch (gitlabState) {
            case "opened" -> "open";
            case "closed" -> "closed";
            case "merged" -> "closed";
            default -> gitlabState;
        };
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean hasBotReviewer(Bot bot, Map<String, Object> payload, Map<String, Object> changes) {
        if (bot.getUsername() == null) {
            return false;
        }
        List<Map<String, Object>> currentReviewers = (List<Map<String, Object>>) payload.get("reviewers");
        if (changes != null) {
            if (!(changes.get("reviewers") instanceof Map<?, ?> reviewersChange)) {
                return false;
            }
            currentReviewers = (List<Map<String, Object>>) reviewersChange.get("current");
        }
        return containsGitLabUser(currentReviewers, bot.getUsername());
    }

    private boolean isBotReviewerReRequestEvent(Bot bot, Map<String, Object> payload, String gitlabAction) {
        return "approved".equals(gitlabAction)
                && hasBotReviewer(bot, payload, null)
                && !isApprovePostReviewActionConfigured(bot);
    }

    private boolean isApprovePostReviewActionConfigured(Bot bot) {
        return bot.getGitIntegration() != null
                && bot.getGitIntegration().getPostReviewAction() == PostReviewAction.APPROVE;
    }

    private boolean containsGitLabUser(List<Map<String, Object>> users, String username) {
        return users != null && users.stream()
                .anyMatch(user -> username.equalsIgnoreCase((String) user.get("username")));
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
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin((String) user.get("username"));
        return owner;
    }

    private List<WebhookPayload.Owner> extractGitLabOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream()
                .map(ownerMap -> {
                    WebhookPayload.Owner owner = new WebhookPayload.Owner();
                    owner.setLogin((String) ownerMap.get("username"));
                    return owner;
                })
                .toList();
    }

}
