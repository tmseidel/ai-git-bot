package org.remus.giteabot.learning.feedback;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Who may give feedback.
 *
 * <p>The bot's configured {@code userWhitelist} applies, with no extra pull-request-author
 * requirement: a permitted reviewer who is not the author can judge the review. An empty whitelist
 * keeps its existing "all commenters" meaning, which for feedback is collection only — it never
 * grants policy rights, and those live in a separate maintainer list resolved from the bot's current
 * agentic-review configuration.</p>
 */
@Service
@RequiredArgsConstructor
public class FeedbackAuthorizationService {

    private final BotService botService;

    /** Whether {@code actor} may submit or retract their own feedback on this bot. */
    public boolean canContribute(Bot bot, String actor) {
        if (bot == null) {
            return false;
        }
        if (actor == null || actor.isBlank()) {
            return false;
        }
        Set<String> allowed = botService.getAllowedUsernames(bot);
        if (allowed.isEmpty()) {
            return true;
        }
        return botService.isUsernameInSet(allowed, actor.trim());
    }
}
