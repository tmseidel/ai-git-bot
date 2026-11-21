package org.remus.giteabot.event.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebhookPayloadTest {

    @Test
    void ownerOf_acceptsNumericAndStringIdsOnly() {
        assertEquals("42", WebhookPayload.Owner.of(42L, "a").getId());
        assertEquals("557058:abc", WebhookPayload.Owner.of("557058:abc", "a").getId());
        assertNull(WebhookPayload.Owner.of(" ", "a").getId());
        assertNull(WebhookPayload.Owner.of(Map.of(), "a").getId());
    }
}
