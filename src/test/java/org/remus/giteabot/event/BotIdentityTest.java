package org.remus.giteabot.event;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.event.model.WebhookPayload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identity rules the provider tests do not reach; the rest is covered through them. */
class BotIdentityTest {

    private final BotIdentity identity = new BotIdentity("ai_bot");

    @Test
    void is_matchesLoginIgnoringCase() {
        assertTrue(identity.is(owner("AI_Bot")));
        assertFalse(identity.is(owner("human")));
        assertFalse(identity.is(null));
    }

    @Test
    void triggered_whenBotWroteTheComment() {
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setUser(owner("ai_bot"));
        WebhookPayload payload = new WebhookPayload();
        payload.setSender(owner("human"));
        payload.setComment(comment);

        assertTrue(identity.triggered(payload));
    }

    private static WebhookPayload.Owner owner(String login) {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin(login);
        return owner;
    }
}
