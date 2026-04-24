package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;
import org.remus.giteabot.review.enrichment.PrContextEnricher;
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

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final SessionService sessionService;
    private final String botUsername;
    private final PrContextEnricher contextEnricher;

    public CodeReviewService(RepositoryApiClient repositoryClient, AiClient aiClient,
                             PromptService promptService, SessionService sessionService,
                             String botUsername, ReviewConfigProperties reviewConfig) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.sessionService = sessionService;
        this.botUsername = botUsername;
        this.contextEnricher = new PrContextEnricher(repositoryClient, reviewConfig);
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
            String headRef = resolveHeadRef(payload);
            String additionalContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);

            String review;
            if (session.getMessages().isEmpty()) {
                log.debug("LLM request [reviewDiff] for PR #{}: systemPrompt length={}, prTitle='{}', prBody length={}, diff length={}, additionalContext length={}",
                        prNumber, systemPrompt != null ? systemPrompt.length() : 0, prTitle,
                        prBody != null ? prBody.length() : 0, diff.length(),
                        additionalContext != null ? additionalContext.length() : 0);
                review = aiClient.reviewDiff(prTitle, prBody, diff, systemPrompt, null, additionalContext);
                log.debug("LLM response [reviewDiff] for PR #{}: length={}, preview='{}'",
                        prNumber, review != null ? review.length() : 0,
                        review != null ? review.substring(0, Math.min(review.length(), 500)) : "null");

                String userSummary = buildPrSummaryMessage(prTitle, prBody);
                sessionService.addMessage(session, "user", userSummary);
                sessionService.addMessage(session, "assistant", review);
            } else {
                String updateMessage = buildPrUpdateMessage(prTitle, diff);
                List<AiMessage> history = sessionService.toAiMessages(session);

                log.debug("LLM request [chat/update] for PR #{}: history size={}, updateMessage length={}, systemPrompt length={}",
                        prNumber, history.size(), updateMessage.length(),
                        systemPrompt != null ? systemPrompt.length() : 0);
                review = aiClient.chat(history, updateMessage, systemPrompt, null);
                log.debug("LLM response [chat/update] for PR #{}: length={}, preview='{}'",
                        prNumber, review != null ? review.length() : 0,
                        review != null ? review.substring(0, Math.min(review.length(), 500)) : "null");

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
        String commentBody = payload.getComment().getBody();
        String commenter = payload.getComment().getUser() != null ? payload.getComment().getUser().getLogin() : null;

        log.info("Handling bot command in comment #{} for PR #{} in {}/{}", commentId, prNumber, owner, repo);

        try {
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);
            boolean knownParticipant = sessionService.hasParticipant(session, commenter);

            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                var prContext = buildPrContextString(payload, diff, owner, repo, prNumber);
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            List<AiMessage> history = sessionService.toAiMessages(session);
            log.debug("LLM request [chat/botCommand] for PR #{}: history size={}, commentBody length={}, systemPrompt length={}",
                    prNumber, history.size(), commentBody.length(),
                    systemPrompt != null ? systemPrompt.length() : 0);
            log.debug("LLM request [chat/botCommand] user message: '{}'",
                    commentBody.substring(0, Math.min(commentBody.length(), 500)));
            String response = aiClient.chat(history, commentBody, systemPrompt, null);
            log.debug("LLM response [chat/botCommand] for PR #{}: length={}, preview='{}'",
                    prNumber, response != null ? response.length() : 0,
                    response != null ? response.substring(0, Math.min(response.length(), 500)) : "null");

            sessionService.addMessage(session, "user", commentBody);
            sessionService.addMessage(session, "assistant", response);
            sessionService.rememberParticipant(session, commenter);

            String formattedResponse = formatBotResponse(response, !knownParticipant);
            repositoryClient.postComment(owner, repo, prNumber, formattedResponse);
            sessionService.compactContextWindow(session);

            log.info("Bot command handled for comment #{} on PR #{} in {}/{}", commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle bot command for comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private @NonNull String buildPrContextString(WebhookPayload payload, String diff,
                                                 String owner, String repo, Long prNumber) {
        String prContext = "This is a pull request. " +
                "Title: " + payload.getIssue().getTitle() + "\n" +
                "Description: " + (payload.getIssue().getBody() != null ? payload.getIssue().getBody() : "N/A");
        if (diff != null && !diff.isBlank()) {
            String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                    ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
            prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
        }

        String headRef = resolveHeadRef(payload);
        String prBody = payload.getIssue() != null ? payload.getIssue().getBody() : null;
        String enrichedContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);
        if (!enrichedContext.isEmpty()) {
            prContext += "\n\n" + enrichedContext;
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
        String commenter = payload.getComment().getUser() != null ? payload.getComment().getUser().getLogin() : null;

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
            boolean knownParticipant = sessionService.hasParticipant(session, commenter);

            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                String prTitle = payload.getIssue() != null ? payload.getIssue().getTitle() : "";
                String prBody = payload.getIssue() != null ? payload.getIssue().getBody() : null;
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

                String headRef = resolveHeadRef(payload);
                String enrichedContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);
                if (!enrichedContext.isEmpty()) {
                    prContext += "\n\n" + enrichedContext;
                }

                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody,
                    session, systemPrompt, null, !knownParticipant);
            sessionService.rememberParticipant(session, commenter);
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

    private String buildInlineCommentAndSend(String filePath, String diffHunk, String commentBody,
                                             ReviewSession session, String systemPrompt,
                                             String modelOverride, boolean sideClarifyingResponse) {
        String contextMessage = buildInlineCommentContext(filePath, diffHunk, commentBody);
        List<AiMessage> history = sessionService.toAiMessages(session);
        log.debug("LLM request [chat/inline] for file '{}': history size={}, contextMessage length={}, systemPrompt length={}",
                filePath, history.size(), contextMessage.length(),
                systemPrompt != null ? systemPrompt.length() : 0);
        log.debug("LLM request [chat/inline] user message: '{}'",
                contextMessage.substring(0, Math.min(contextMessage.length(), 500)));
        String response = aiClient.chat(history, contextMessage, systemPrompt, modelOverride);
        log.debug("LLM response [chat/inline] for file '{}': length={}, preview='{}'",
                filePath, response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(response.length(), 500)) : "null");

        sessionService.addMessage(session, "user", contextMessage);
        sessionService.addMessage(session, "assistant", response);

        return formatBotResponse(response, sideClarifyingResponse);
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

            List<ReviewComment> comments = repositoryClient.getReviewComments(
                    owner, repo, prNumber, latestReview.getId());

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

                String headRef = resolveHeadRef(payload);
                String enrichedContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);
                if (!enrichedContext.isEmpty()) {
                    prContext += "\n\n" + enrichedContext;
                }

                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            for (ReviewComment reviewComment : botMentionComments) {
                try {
                    boolean knownParticipant = sessionService.hasParticipant(session, reviewComment.getUserLogin());
                    processReviewComment(owner, repo, prNumber, reviewComment,
                            session, systemPrompt, null, !knownParticipant);
                    sessionService.rememberParticipant(session, reviewComment.getUserLogin());
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
                                      boolean sideClarifyingResponse) {
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

        var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody,
                session, systemPrompt, modelOverride, sideClarifyingResponse);
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
                "\n\n---\n*Automated review by AI Git Bot*";
    }

    String formatBotResponse(String response) {
        return formatBotResponse(response, false);
    }

    String formatBotResponse(String response, boolean sideClarifyingResponse) {
        String note = sideClarifyingResponse
                ? "\n\n> Side-clarifying response for another PR participant; based on the existing review context."
                : "";
        return "## 🤖 Bot Response\n\n" + response + note +
                "\n\n---\n*Response by AI Git Bot*";
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
        StringBuilder sb = new StringBuilder();
        sb.append("Someone left an inline review comment on file `").append(filePath).append("`.\n\n");
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

    String resolveHeadRef(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null) {
            return payload.getPullRequest().getHead().getRef();
        }
        try {
            String owner = payload.getRepository().getOwner().getLogin();
            String repo = payload.getRepository().getName();
            return repositoryClient.getDefaultBranch(owner, repo);
        } catch (Exception e) {
            log.debug("Could not resolve head ref, falling back to 'main': {}", e.getMessage());
            return "main";
        }
    }

    String gatherAdditionalContext(String owner, String repo, Long prNumber,
                                   String diff, String headRef, String prBody) {
        try {
            return contextEnricher.buildEnrichedContext(owner, repo, prNumber, diff, headRef, prBody);
        } catch (Exception e) {
            log.warn("Failed to gather additional context for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage());
            return "";
        }
    }
}
