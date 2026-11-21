package org.remus.giteabot.event;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.model.RepositoryUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identity rules the provider tests do not reach; the rest is covered through them. */
class BotIdentityTest {

    private final BotIdentity identity = new BotIdentity(new RepositoryUser("42", "ai_bot", null, null));

    @Test
    void is_matchesById() {
        assertTrue(identity.is(WebhookPayload.Owner.of(42, "renamed_bot")));
        assertFalse(identity.is(WebhookPayload.Owner.of(7, "ai_bot")));
        assertFalse(identity.is(null));
    }

    @Test
    void is_neverMatchesAUserWithoutId() {
        assertFalse(identity.is(WebhookPayload.Owner.of(null, "ai_bot")));
    }

    @Test
    void alias_isTheAccountsUsername() {
        assertEquals("@ai_bot", identity.alias());
    }

    @Test
    void triggered_whenBotWroteTheComment() {
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setUser(WebhookPayload.Owner.of("42", "ai_bot"));
        WebhookPayload payload = new WebhookPayload();
        payload.setSender(WebhookPayload.Owner.of("7", "human"));
        payload.setComment(comment);

        assertTrue(identity.triggered(payload));
    }
}
