package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;

import java.util.List;

/**
 * Core code-review business logic.  Not a Spring-managed singleton — instances
 * are created per-bot by {@link org.remus.giteabot.admin.BotWebhookService}
 * with the bot's own {@link AiClient} and {@link RepositoryApiClient}.
 */
@Slf4j
public class CodeReviewService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60000;
    static final String SIDE_CLARIFICATION_MARKER = "[Side clarification for another PR participant]";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final SessionService sessionService;
    private final String botUsername;

    public CodeReviewService(RepositoryApiClient repositoryClient, AiClient aiClient,
                             PromptService promptService, SessionService sessionService,
                             String botUsername) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.sessionService = sessionService;
        this.botUsername = botUsername;
    }

    public void reviewPullRequest(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting code review for PR #{} '{}' in {}/{}, prompt={}", prNumber, prTitle, owner, repo, promptName);

        try {
            String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
            if (diff == null || diff.isBlank()) {
                log.warn("No diff found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);
            sessionService.updatePrContext(session, prTitle, prBody);

            String review;
            if (session.getMessages().isEmpty()) {
                review = aiClient.reviewDiff(prTitle, prBody, diff, systemPrompt, null);

                String userSummary = buildPrSummaryMessage(prTitle, prBody);
                sessionService.addMessage(session, "user", userSummary);
                sessionService.addMessage(session, "assistant", review);
            } else {
                String updateMessage = buildPrUpdateMessage(prTitle, diff);
                List<AiMessage> history = sessionService.toAiMessages(session);

                review = aiClient.chat(history, updateMessage, systemPrompt, null);

                sessionService.addMessage(session, "user", updateMessage);
                sessionService.addMessage(session, "assistant", review);
            }

            String commentBody = formatReviewComment(review);
            repositoryClient.postReviewComment(owner, repo, prNumber, commentBody);

            sessionService.compactContextWindow(session);

            log.info("Code review completed for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Code review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
        }
    }

    public void handleBotCommand(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue().getNumber();
        Long commentId = payload.getComment().getId();

        log.info("Handling bot command in comment #{} for PR #{} in {}/{}", commentId, prNumber, owner, repo);

        try {
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);
            sessionService.updatePrContext(session,
                    payload.getIssue() != null ? payload.getIssue().getTitle() : null,
                    payload.getIssue() != null ? payload.getIssue().getBody() : null);

            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                var prContext = buildPrContextString(payload, diff);
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            String userMessage = buildBotCommandMessage(payload, session);
            List<AiMessage> history = sessionService.toAiMessages(session);
            String response = aiClient.chat(history, userMessage, systemPrompt, null);

            sessionService.addMessage(session, "user", userMessage);
            sessionService.addMessage(session, "assistant", response);

            String formattedResponse = formatBotResponse(response, isSideClarification(payload));
            repositoryClient.postComment(owner, repo, prNumber, formattedResponse);

            sessionService.compactContextWindow(session);

            log.info("Bot command handled for comment #{} on PR #{} in {}/{}", commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle bot command for comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private static @NonNull String buildPrContextString(WebhookPayload payload, String diff) {
        String prContext = "This is a pull request. " +
                "Title: " + payload.getIssue().getTitle() + "\n" +
                "Description: " + (payload.getIssue().getBody() != null ? payload.getIssue().getBody() : "N/A");
        if (diff != null && !diff.isBlank()) {
            String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                    ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
            prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
        }
        return prContext;
    }

    public void handleInlineComment(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = resolvePrNumber(payload);
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();
        String filePath = payload.getComment().getPath();
        String diffHunk = payload.getComment().getDiffHunk();
        Integer line = payload.getComment().getLine();

        log.info("Handling inline comment #{} on file {} for PR #{} in {}/{}",
                commentId, filePath, prNumber, owner, repo);

        try {
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to inline comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);
            sessionService.updatePrContext(session, extractPrTitle(payload), extractPrBody(payload));

            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                String prTitle = extractPrTitle(payload);
                String prBody = extractPrBody(payload);
                String prContext = "This is a pull request in " + owner + "/" + repo + ".";
                if (prTitle != null && !prTitle.isBlank()) {
                    prContext += " Title: " + prTitle;
                }
                if (prBody != null && !prBody.isBlank()) {
                    prContext += "\nDescription: " + prBody;
                }
                if (diff != null && !diff.isBlank()) {
                    String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                            ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
                    prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
                }
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            var formattedResponse = buildInlineCommentAndSend(payload, filePath, diffHunk, commentBody,
                    session, systemPrompt, null);
            if (line != null && line > 0) {
                try {
                    repositoryClient.postInlineReviewComment(owner, repo, prNumber,
                            filePath, line, formattedResponse);
                } catch (Exception e) {
                    log.warn("Failed to post inline reply, falling back to regular comment: {}", e.getMessage());
                    repositoryClient.postComment(owner, repo, prNumber, formattedResponse);
                }
            } else {
                repositoryClient.postComment(owner, repo, prNumber, formattedResponse);
            }

            sessionService.compactContextWindow(session);

            log.info("Inline comment handled for comment #{} on PR #{} in {}/{}",
                    commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle inline comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private String buildInlineCommentAndSend(WebhookPayload payload, String filePath, String diffHunk,
                                             String commentBody, ReviewSession session,
                                             String systemPrompt, String modelOverride) {
        String contextMessage = buildInlineCommentContext(payload, session, filePath, diffHunk, commentBody);

        List<AiMessage> history = sessionService.toAiMessages(session);
        String response = aiClient.chat(history, contextMessage, systemPrompt, modelOverride);

        sessionService.addMessage(session, "user", contextMessage);
        sessionService.addMessage(session, "assistant", response);

        return formatBotResponse(response, isSideClarification(payload));
    }

    public void handleReviewSubmitted(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("Handling review submitted event for PR #{} in {}/{}", prNumber, owner, repo);

        try {
            String systemPrompt = promptService.getSystemPrompt(promptName);

            List<Review> reviews = repositoryClient.getReviews(owner, repo, prNumber);
            if (reviews.isEmpty()) {
                log.warn("No reviews found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            Review latestReview = reviews.stream()
                    .reduce((a, b) -> (b.getId() != null && (a.getId() == null || b.getId() > a.getId())) ? b : a)
                    .orElse(null);

            if (latestReview == null || latestReview.getId() == null) {
                log.warn("No valid review found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            log.info("Processing latest review #{} for PR #{} in {}/{}", latestReview.getId(), prNumber, owner, repo);

            List<ReviewComment> comments = repositoryClient.getReviewComments(owner, repo, prNumber, latestReview.getId());

            String botAlias = (botUsername != null && !botUsername.isBlank()) ? "@" + botUsername : "";
            List<ReviewComment> botMentionComments = comments.stream()
                    .filter(c -> c.getBody() != null && c.getBody().contains(botAlias))
                    .filter(c -> !isBotComment(c, botUsername))
                    .toList();

            if (botMentionComments.isEmpty()) {
                log.debug("No bot-mentioning comments in review #{} for PR #{}", latestReview.getId(), prNumber);
                return;
            }

            log.info("Found {} bot-mentioning comment(s) in review #{} for PR #{}",
                    botMentionComments.size(), latestReview.getId(), prNumber);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);
            sessionService.updatePrContext(session,
                    payload.getPullRequest() != null ? payload.getPullRequest().getTitle() : null,
                    payload.getPullRequest() != null ? payload.getPullRequest().getBody() : null);

            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                String prTitle = payload.getPullRequest().getTitle();
                String prBody = payload.getPullRequest().getBody();
                String prContext = "This is a pull request in " + owner + "/" + repo + ".";
                if (prTitle != null && !prTitle.isBlank()) {
                    prContext += " Title: " + prTitle;
                }
                if (prBody != null && !prBody.isBlank()) {
                    prContext += "\nDescription: " + prBody;
                }
                if (diff != null && !diff.isBlank()) {
                    String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                            ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
                    prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
                }
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            for (ReviewComment reviewComment : botMentionComments) {
                try {
                    processReviewComment(owner, repo, prNumber, reviewComment, session, systemPrompt, null,
                            payload.getSender() != null ? payload.getSender().getLogin() : null);
                } catch (Exception e) {
                    log.error("Failed to process review comment #{} on PR #{} in {}/{}: {}",
                            reviewComment.getId(), prNumber, owner, repo, e.getMessage(), e);
                }
            }

            sessionService.compactContextWindow(session);

            log.info("Review submitted event handled for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle review submitted event for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private void processReviewComment(String owner, String repo, Long prNumber,
                                      ReviewComment reviewComment, ReviewSession session,
                                      String systemPrompt, String modelOverride,
                                      String prAuthorLogin) {
        Long commentId = reviewComment.getId();
        String filePath = reviewComment.getPath();
        String diffHunk = reviewComment.getDiffHunk();
        String commentBody = reviewComment.getBody();
        Integer line = reviewComment.getLine();

        log.info("Processing review comment #{} on file {} for PR #{}", commentId, filePath, prNumber);

        try {
            repositoryClient.addReaction(owner, repo, commentId, "eyes");
        } catch (Exception e) {
            log.warn("Failed to add reaction to review comment #{}: {}", commentId, e.getMessage());
        }

        WebhookPayload synthesizedPayload = buildPayloadFromReviewComment(owner, repo, prNumber,
                reviewComment, session, prAuthorLogin);

        var formattedResponse = buildInlineCommentAndSend(synthesizedPayload, filePath, diffHunk, commentBody,
                session, systemPrompt, modelOverride);
        if (line != null && line > 0) {
            try {
                repositoryClient.postInlineReviewComment(owner, repo, prNumber,
                        filePath, line, formattedResponse);
            } catch (Exception e) {
                log.warn("Failed to post inline reply for review comment #{}, falling back to regular comment: {}",
                        commentId, e.getMessage());
                repositoryClient.postComment(owner, repo, prNumber, formattedResponse);
            }
        } else {
            repositoryClient.postComment(owner, repo, prNumber, formattedResponse);
        }
    }

    public void handlePrClosed(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("PR #{} in {}/{} was closed, deleting session", prNumber, owner, repo);
        sessionService.deleteSession(owner, repo, prNumber);
    }

    String formatReviewComment(String review) {
        return "## 🤖 AI Code Review\n\n" + review +
                "\n\n---\n*Automated review by AI Gitea Bot*";
    }

    String formatBotResponse(String response) {
        return formatBotResponse(response, false);
    }

    String formatBotResponse(String response, boolean sideClarification) {
        String prefix = sideClarification ? SIDE_CLARIFICATION_MARKER + "\n\n" : "";
        return "## 🤖 Bot Response\n\n" + prefix + response +
                "\n\n---\n*Response by AI Gitea Bot*";
    }

    private String buildPrSummaryMessage(String prTitle, String prBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("I opened a pull request titled '").append(prTitle).append("'.");
        if (prBody != null && !prBody.isBlank()) {
            sb.append(" Description: ").append(prBody);
        }
        sb.append(" Please review it.");
        return sb.toString();
    }

    private String buildPrUpdateMessage(String prTitle, String diff) {
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
        return "The pull request '" + prTitle + "' has been updated with new changes. " +
                "Please review the updated diff:\n```diff\n" + truncatedDiff + "\n```";
    }

    String buildInlineCommentContext(String filePath, String diffHunk, String commentBody) {
        return buildInlineCommentContext(null, null, filePath, diffHunk, commentBody);
    }

    String buildInlineCommentContext(WebhookPayload payload, ReviewSession session,
                                     String filePath, String diffHunk, String commentBody) {
        StringBuilder sb = new StringBuilder();
        if (isSideClarification(payload)) {
            sb.append("This is a side clarification request from another pull request participant, not the PR author. ");
            String commenter = extractCommentAuthor(payload);
            if (commenter != null && !commenter.isBlank()) {
                sb.append("The participant is `").append(commenter).append("`. ");
            }
            sb.append("Use the existing PR review context to answer helpfully, but make clear the reply is only a side clarification and not a new main review decision.\n\n");
        }
        sb.append("Someone left an inline review comment on file `").append(filePath).append("`.\n\n");
        appendPrSummary(sb, session);
        if (diffHunk != null && !diffHunk.isBlank()) {
            sb.append("Code context (diff hunk):\n```diff\n").append(diffHunk).append("\n```\n\n");
        }
        sb.append("Their comment/question:\n").append(commentBody);
        return sb.toString();
    }

    Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        return null;
    }

    private boolean isBotComment(ReviewComment comment, String botUsername) {
        if (botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return comment.getUserLogin() != null
                && botUsername.equalsIgnoreCase(comment.getUserLogin());
    }

    private boolean isSideClarification(WebhookPayload payload) {
        if (payload == null || payload.getComment() == null || payload.getComment().getUser() == null) {
            return false;
        }
        String commentAuthor = payload.getComment().getUser().getLogin();
        if (commentAuthor == null || commentAuthor.isBlank()) {
            return false;
        }
        String prAuthor = extractPrAuthor(payload);
        if (prAuthor == null || prAuthor.isBlank()) {
            return false;
        }
        return !commentAuthor.equalsIgnoreCase(prAuthor);
    }

    private String buildBotCommandMessage(WebhookPayload payload, ReviewSession session) {
        String commentBody = payload.getComment().getBody();
        if (!isSideClarification(payload)) {
            return commentBody;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("This is a side clarification request from another pull request participant, not the PR author. ");
        String commenter = extractCommentAuthor(payload);
        if (commenter != null && !commenter.isBlank()) {
            sb.append("The participant is `").append(commenter).append("`. ");
        }
        String prAuthor = extractPrAuthor(payload);
        if (prAuthor != null && !prAuthor.isBlank()) {
            sb.append("The PR author is `").append(prAuthor).append("`. ");
        }
        sb.append("Use the known pull request context and answer only as a side clarification, not as a new main review.\n\n");
        appendPrSummary(sb, session);
        sb.append("Their comment/question:\n").append(commentBody);
        return sb.toString();
    }

    private void appendPrSummary(StringBuilder sb, ReviewSession session) {
        if (session == null) {
            return;
        }
        if (session.getPrTitle() != null && !session.getPrTitle().isBlank()) {
            sb.append("Pull request title: ").append(session.getPrTitle()).append("\n");
        }
        if (session.getPrBody() != null && !session.getPrBody().isBlank()) {
            sb.append("Pull request description: ").append(session.getPrBody()).append("\n\n");
        }
    }

    private String extractCommentAuthor(WebhookPayload payload) {
        return payload != null && payload.getComment() != null && payload.getComment().getUser() != null
                ? payload.getComment().getUser().getLogin()
                : null;
    }

    private String extractPrAuthor(WebhookPayload payload) {
        return payload != null && payload.getSender() != null ? payload.getSender().getLogin() : null;
    }

    private String extractPrTitle(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getTitle() != null) {
            return payload.getPullRequest().getTitle();
        }
        if (payload.getIssue() != null) {
            return payload.getIssue().getTitle();
        }
        return null;
    }

    private String extractPrBody(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getBody() != null) {
            return payload.getPullRequest().getBody();
        }
        if (payload.getIssue() != null) {
            return payload.getIssue().getBody();
        }
        return null;
    }

    private WebhookPayload buildPayloadFromReviewComment(String owner, String repo, Long prNumber,
                                                         ReviewComment reviewComment, ReviewSession session,
                                                         String prAuthorLogin) {
        WebhookPayload payload = new WebhookPayload();

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        WebhookPayload.Owner repoOwner = new WebhookPayload.Owner();
        repoOwner.setLogin(owner);
        repository.setOwner(repoOwner);
        payload.setRepository(repository);

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        pr.setTitle(session.getPrTitle());
        pr.setBody(session.getPrBody());
        payload.setPullRequest(pr);

        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(reviewComment.getId());
        comment.setBody(reviewComment.getBody());
        comment.setPath(reviewComment.getPath());
        comment.setDiffHunk(reviewComment.getDiffHunk());
        comment.setLine(reviewComment.getLine());
        if (reviewComment.getUserLogin() != null) {
            WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
            commentUser.setLogin(reviewComment.getUserLogin());
            comment.setUser(commentUser);
        }
        payload.setComment(comment);

        if (prAuthorLogin != null && !prAuthorLogin.isBlank()) {
            WebhookPayload.Owner sender = new WebhookPayload.Owner();
            sender.setLogin(prAuthorLogin);
            payload.setSender(sender);
        }

        if (session.getPrTitle() != null || session.getPrBody() != null) {
            WebhookPayload.Issue issue = new WebhookPayload.Issue();
            issue.setNumber(prNumber);
            issue.setTitle(session.getPrTitle());
            issue.setBody(session.getPrBody());
            issue.setPullRequest(new WebhookPayload.IssuePullRequest());
            payload.setIssue(issue);
        }

        return payload;
    }
}
