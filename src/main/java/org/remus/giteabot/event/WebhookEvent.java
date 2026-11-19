package org.remus.giteabot.event;

import lombok.Builder;
import org.remus.giteabot.event.model.WebhookPayload;

/**
 * A webhook after provider translation: what happened, the provider-neutral payload,
 * and the facts about the bot that only the provider can establish.
 * <p>
 * Translators fill in every bot-relative fact ({@code fromBot}, {@code botIsReviewer},
 * {@code botMentioned}) because where a payload carries its users' ids, and how it marks
 * a mention, differs per provider — the numeric id on Gitea/GitHub/GitLab, the
 * {@code account_id} on Bitbucket (whose mentions are {@code @{account_id}} markup), the
 * identity GUID on Azure DevOps; see {@link BotIdentity}. The dispatcher never inspects
 * raw payloads or compares users itself.
 *
 * @param kind          what happened
 * @param payload       the translated payload; {@code null} only for {@link WebhookEventKind#IGNORED}
 * @param fromBot       the event was triggered by the bot itself and must not be acted on
 * @param botIsReviewer the bot is currently among the pull request's requested reviewers
 * @param botMentioned  the comment body addresses the bot (only meaningful for comment kinds)
 * @param botAlias      the readable alias the bot is addressed by, e.g. {@code @claude_bot};
 *                      Bitbucket rewrites its {@code @{account_id}} markup to this before
 *                      the payload reaches the dispatcher
 * @param ignoreReason  why the event is ignored, for logging; only set for {@link WebhookEventKind#IGNORED}
 */
@Builder
public record WebhookEvent(
        WebhookEventKind kind,
        WebhookPayload payload,
        boolean fromBot,
        boolean botIsReviewer,
        boolean botMentioned,
        String botAlias,
        String ignoreReason) {

    public WebhookEvent {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (kind != WebhookEventKind.IGNORED && payload == null) {
            throw new IllegalArgumentException("payload must not be null for " + kind);
        }
    }

    /**
     * A non-ignored event of {@code kind}, with every bot-relative fact established by
     * matching {@code bot} against the payload.
     */
    public static WebhookEvent of(WebhookEventKind kind, WebhookPayload payload, BotIdentity bot) {
        return WebhookEvent.builder()
                .kind(kind)
                .payload(payload)
                .fromBot(bot.triggered(payload))
                .botIsReviewer(bot.isReviewer(payload))
                .botMentioned(bot.isMentionedIn(payload))
                .botAlias(bot.alias())
                .build();
    }

    /** An event the bot does not react to. */
    public static WebhookEvent ignored(String reason) {
        return WebhookEvent.builder()
                .kind(WebhookEventKind.IGNORED)
                .ignoreReason(reason)
                .build();
    }
}
