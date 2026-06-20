package org.remus.giteabot.prworkflow.review;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.session.SessionService;
import org.springframework.stereotype.Component;

/**
 * Factory for per-bot {@link CodeReviewService} instances.
 *
 * <p>Extracted from {@link org.remus.giteabot.admin.BotWebhookService} in
 * milestone M1 so both the new {@link ReviewWorkflow} and the legacy
 * code-review handlers (bot commands, inline review replies, …) can share a
 * single construction path. Behaviourally identical to the previous private
 * {@code BotWebhookService#createCodeReviewService(...)} helper.</p>
 *
 * <p>All callers must use the full variant that accepts explicit chunking
 * parameters. The chunking values are sourced from workflow configuration
 * (or {@code review.chunking.*} application properties as a fallback) and
 * passed through by {@link ReviewWorkflow#run}.</p>
 */
@Component
@RequiredArgsConstructor
public class CodeReviewServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final SessionService sessionService;
    private final ReviewConfigProperties reviewConfig;

    /**
     * Builds a fresh {@link CodeReviewService} for the given bot, using the
     * bot's configured AI integration, the supplied repository client, and
     * explicit diff-chunking parameters sourced from workflow configuration.
     */
    public CodeReviewService create(Bot bot, RepositoryApiClient repoClient,
                                    int maxDiffCharsPerChunk, int maxDiffChunks,
                                    int retryTruncatedChunkChars) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        if (bot.getSystemPrompt().getId() == null) {
            throw new IllegalStateException("Bot system prompt must be persisted");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        String sessionPromptKey = "system-prompt:" + bot.getSystemPrompt().getId();
        return new CodeReviewService(repoClient, aiClient, sessionService, bot.getUsername(),
                reviewConfig, sessionPromptKey, bot.getSystemPrompt().getReviewSystemPrompt(),
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
    }
}
