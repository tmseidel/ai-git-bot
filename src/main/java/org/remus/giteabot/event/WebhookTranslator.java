package org.remus.giteabot.event;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.repository.RepositoryType;

import java.util.Map;

/**
 * Everything provider-specific about one Git provider's webhooks: checking the request's
 * signature, and translating the raw webhook into a provider-neutral {@link WebhookEvent}.
 * <p>
 * A translator only classifies: it decides which {@link WebhookEventKind} an incoming
 * request is and establishes the bot-relative facts on the event. It must not decide
 * whether the bot acts (run-on-create, run-on-update, mention gating) and must not call
 * the {@code BotWebhookService} handlers — that is {@link org.remus.giteabot.webhook.WebhookEventDispatcher}'s job,
 * so the rules are the same for every provider.
 * <p>
 * Translators may still call the provider's API or keep state when the payload alone
 * cannot tell what happened (Bitbucket resolving the bot account, Azure DevOps
 * comparing an update against the previous one).
 */
public interface WebhookTranslator {

    /** The provider this translator handles. */
    RepositoryType providerType();

    /**
     * Checks the signature or shared-secret token the provider sent with the request. Only
     * called for bots with a signing secret configured, before the body is parsed.
     *
     * @param signingSecret the bot's signing secret; never blank
     * @param headers       all request headers; names are matched case-insensitively
     * @param rawBody       the exact request body the signature was computed over
     * @return whether the request carries a valid signature or token
     * @see WebhookSignatures
     */
    boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody);

    /**
     * Classifies an incoming webhook.
     *
     * @param bot     the bot the webhook was addressed to
     * @param headers all request headers; names are matched case-insensitively
     * @param payload the parsed JSON body
     * @return the event; {@link WebhookEvent#ignored(String)} for anything the bot does not react to
     */
    WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload);
}
