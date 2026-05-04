package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
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
    private final SessionService sessionService;
    private final String botUsername;
    private final String sessionPromptKey;
    private final String reviewSystemPrompt;
    private final PrContextEnricher contextEnricher;

    public CodeReviewService(RepositoryApiClient repositoryClient, AiClient aiClient,
                             SessionService sessionService, String botUsername, ReviewConfigProperties reviewConfig,
                             String sessionPromptKey, String reviewSystemPrompt) {
        if (sessionPromptKey == null || sessionPromptKey.isBlank()) {
            throw new IllegalArgumentException("Session prompt key is required");
        }
        if (reviewSystemPrompt == null || reviewSystemPrompt.isBlank()) {
            throw new IllegalArgumentException("Review system prompt is required");
        }
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.sessionService = sessionService;
        this.botUsername = botUsername;
        this.sessionPromptKey = sessionPromptKey;
        this.reviewSystemPrompt = reviewSystemPrompt;
        this.contextEnricher = new PrContextEnricher(repositoryClient, reviewConfig);
    }

    public boolean reviewPullRequest(WebhookPayload payload, String promptName) {
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
                return false;
            }

            String systemPrompt = reviewSystemPrompt;

            // Build enriched context for better review quality
            String headRef = resolveHeadRef(payload);
            String additionalContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, sessionPromptKey);

            String review;
            if (session.getMessages().isEmpty()) {
                // Initial review: use the chunked diff review with enriched context
                log.debug("LLM request [reviewDiff] for PR #{}: systemPrompt length={}, prTitle='{}', prBody length={}, diff length={}, additionalContext length={}",
                        prNumber, systemPrompt != null ? systemPrompt.length() : 0, prTitle,
                        prBody != null ? prBody.length() : 0, diff.length(),
                        additionalContext != null ? additionalContext.length() : 0);
                review = aiClient.reviewDiff(prTitle, prBody, diff, systemPrompt, null, additionalContext);
                log.debug("LLM response [reviewDiff] for PR #{}: length={}, preview='{}'",
                        prNumber, review != null ? review.length() : 0,
                        review != null ? review.substring(0, Math.min(review.length(), 500)) : "null");

                // Store a summary user message and the review in the session
                String userSummary = buildPrSummaryMessage(prTitle, prBody);
                sessionService.addMessage(session, "user", userSummary);
                sessionService.addMessage(session, "assistant", review);
            } else {
                // PR was updated: use conversation context with new diff
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

            // Compact context window to reduce memory/token usage for subsequent calls
            sessionService.compactContextWindow(session);

            log.info("Code review completed for PR #{} in {}/{}", prNumber, owner, repo);
            return true;
        } catch (Exception e) {
            log.error("Code review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
            return false;
        }
    }

    public void handleBotCommand(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue().getNumber();
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();

        log.info("Handling bot command in comment #{} for PR #{} in {}/{}", commentId, prNumber, owner, repo);

        try {
            // Add eyes reaction to acknowledge the comment
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = reviewSystemPrompt;

            // Get or create session
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, sessionPromptKey);

            // If session is empty, add context from the PR
            if (session.getMessages().isEmpty()) {
                String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
                var prContext = buildPrContextString(payload, diff, owner, repo, prNumber);
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            // Send the comment as a new message in the conversation
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

            // Store messages in session
            sessionService.addMessage(session, "user", commentBody);
            sessionService.addMessage(session, "assistant", response);

            // Post the response as a comment on the PR
            String formattedResponse = formatBotResponse(response);
            repositoryClient.postPullRequestComment(owner, repo, prNumber, formattedResponse);

            // Compact context window to reduce memory/token usage
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
            // Truncate diff to avoid excessively large context
            String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                    ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
            prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
        }

        // Add enriched context
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

        log.info("Handling inline comment #{} on file {} for PR #{} in {}/{}",
                commentId, filePath, prNumber, owner, repo);

        try {
            // Add eyes reaction to acknowledge the comment
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to inline comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = reviewSystemPrompt;

            // Get or create session for conversation context
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, sessionPromptKey);

            // If session is empty, add PR context
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

                // Add enriched context
                String headRef = resolveHeadRef(payload);
                String enrichedContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);
                if (!enrichedContext.isEmpty()) {
                    prContext += "\n\n" + enrichedContext;
                }

                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody, session, systemPrompt, null);
            if (line != null && line > 0) {
                try {
                    repositoryClient.postInlineReviewComment(owner, repo, prNumber,
                            filePath, line, formattedResponse);
                } catch (Exception e) {
                    log.warn("Failed to post inline reply, falling back to regular comment: {}", e.getMessage());
                    repositoryClient.postPullRequestComment(owner, repo, prNumber, formattedResponse);
                }
            } else {
                repositoryClient.postPullRequestComment(owner, repo, prNumber, formattedResponse);
            }

            // Compact context window to reduce memory/token usage
            sessionService.compactContextWindow(session);

            log.info("Inline comment handled for comment #{} on PR #{} in {}/{}",
                    commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle inline comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private String buildInlineCommentAndSend(String filePath, String diffHunk, String commentBody, ReviewSession session, String systemPrompt, String modelOverride) {
        // Build context message with file/code context
        String contextMessage = buildInlineCommentContext(filePath, diffHunk, commentBody);

        // Send to AI
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

        // Store in session
        sessionService.addMessage(session, "user", contextMessage);
        sessionService.addMessage(session, "assistant", response);

        // Reply inline at the same file/line if possible
        return formatBotResponse(response);
    }

    /**
     * Handles a review submitted event (action: "reviewed").
     * Gitea sends this when a user submits a review with inline comments.
     * The individual comments are NOT in the webhook payload, so we fetch them
     * from the repository API, filter for bot mentions, and respond to each.
     */
    public void handleReviewSubmitted(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("Handling review submitted event for PR #{} in {}/{}", prNumber, owner, repo);

        try {
            String systemPrompt = reviewSystemPrompt;

            // Fetch all reviews for the PR to find the latest one
            List<Review> reviews = repositoryClient.getReviews(owner, repo, prNumber);
            if (reviews.isEmpty()) {
                log.warn("No reviews found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            // Take the latest review (highest ID)
            Review latestReview = reviews.stream()
                    .reduce((a, b) -> (b.getId() != null && (a.getId() == null || b.getId() > a.getId())) ? b : a)
                    .orElse(null);

            if (latestReview == null || latestReview.getId() == null) {
                log.warn("No valid review found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            log.info("Processing latest review #{} for PR #{} in {}/{}", latestReview.getId(), prNumber, owner, repo);

            // Fetch comments for this review
            List<ReviewComment> comments = repositoryClient.getReviewComments(
                    owner, repo, prNumber, latestReview.getId());

            // Filter for comments that mention the bot, excluding the bot's own comments
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

            // Get or create session
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, sessionPromptKey);

            // If session is empty, add PR context
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

                // Add enriched context
                String headRef = resolveHeadRef(payload);
                String enrichedContext = gatherAdditionalContext(owner, repo, prNumber, diff, headRef, prBody);
                if (!enrichedContext.isEmpty()) {
                    prContext += "\n\n" + enrichedContext;
                }

                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            // Process each bot-mentioning comment
            for (ReviewComment reviewComment : botMentionComments) {
                try {
                    processReviewComment(owner, repo, prNumber, reviewComment,
                            session, systemPrompt, null);
                } catch (Exception e) {
                    log.error("Failed to process review comment #{} on PR #{} in {}/{}: {}",
                            reviewComment.getId(), prNumber, owner, repo, e.getMessage(), e);
                }
            }

            // Compact context window after processing all comments
            sessionService.compactContextWindow(session);

            log.info("Review submitted event handled for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle review submitted event for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private void processReviewComment(String owner, String repo, Long prNumber,
                                      ReviewComment reviewComment, ReviewSession session,
                                      String systemPrompt, String modelOverride) {
        Long commentId = reviewComment.getId();
        String filePath = reviewComment.getPath();
        String diffHunk = reviewComment.getDiffHunk();
        String commentBody = reviewComment.getBody();
        Integer line = reviewComment.getLine();

        log.info("Processing review comment #{} on file {} for PR #{}", commentId, filePath, prNumber);

        // Add eyes reaction to acknowledge
        try {
            repositoryClient.addReaction(owner, repo, commentId, "eyes");
        } catch (Exception e) {
            log.warn("Failed to add reaction to review comment #{}: {}", commentId, e.getMessage());
        }

        // Build context message
        var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody, session, systemPrompt, modelOverride);
        if (line != null && line > 0) {
            try {
                repositoryClient.postInlineReviewComment(owner, repo, prNumber,
                        filePath, line, formattedResponse);
            } catch (Exception e) {
                log.warn("Failed to post inline reply for review comment #{}, falling back to regular comment: {}",
                        commentId, e.getMessage());
                repositoryClient.postPullRequestComment(owner, repo, prNumber, formattedResponse);
            }
        } else {
            repositoryClient.postPullRequestComment(owner, repo, prNumber, formattedResponse);
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
        return "## 🤖 Bot Response\n\n" + response +
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
        // Truncate diff for conversation context to avoid excessively large messages
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

    /**
     * Checks whether a review comment was written by the bot itself.
     * Prevents the bot from responding to its own comments in review-submitted events.
     */
    private boolean isBotComment(ReviewComment comment, String botUsername) {
        if (botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return comment.getUserLogin() != null
                && botUsername.equalsIgnoreCase(comment.getUserLogin());
    }

    /**
     * Resolves the head branch ref from the webhook payload.
     * Tries PullRequest.head.ref first, then falls back to the default branch.
     */
    String resolveHeadRef(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null) {
            return payload.getPullRequest().getHead().getRef();
        }
        // Fallback: try to get the default branch
        try {
            String owner = payload.getRepository().getOwner().getLogin();
            String repo = payload.getRepository().getName();
            return repositoryClient.getDefaultBranch(owner, repo);
        } catch (Exception e) {
            log.debug("Could not resolve head ref, falling back to 'main': {}", e.getMessage());
            return "main";
        }
    }

    /**
     * Gathers additional context for a PR review using the PrContextEnricher.
     * Returns an empty string if context gathering fails.
     */
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
