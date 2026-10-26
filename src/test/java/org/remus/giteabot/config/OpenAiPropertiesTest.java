package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider-level {@code chat_template_kwargs} setting: whatever the served
 * model's chat template expects must reach the request with its JSON type
 * intact, and a malformed object must fail at startup.
 */
class OpenAiPropertiesTest {

    private static OpenAiProperties properties(String json) {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setChatTemplateKwargs(json);
        properties.parseChatTemplateKwargs();
        return properties;
    }

    @Test
    void keepsJsonValueTypes() {
        Map<String, Object> parsed = properties(
                "{\"enable_thinking\": false, \"reasoning_effort\": 2, \"effort\": \"low\"}")
                .getParsedChatTemplateKwargs();

        assertEquals(Boolean.FALSE, parsed.get("enable_thinking"));
        assertEquals(2, parsed.get("reasoning_effort"));
        assertEquals("low", parsed.get("effort"));
    }

    @Test
    void keepsNestedObjects() {
        Map<String, Object> parsed = properties("{\"thinking\": {\"budget\": 1024, \"dynamic\": true}}")
                .getParsedChatTemplateKwargs();

        Map<?, ?> thinking = assertInstanceOf(Map.class, parsed.get("thinking"));
        assertEquals(1024, thinking.get("budget"));
        assertEquals(Boolean.TRUE, thinking.get("dynamic"));
    }

    @Test
    void unsetSendsNothing() {
        assertNull(new OpenAiProperties().getParsedChatTemplateKwargs());
        assertNull(properties(null).getParsedChatTemplateKwargs());
        assertNull(properties("").getParsedChatTemplateKwargs());
        assertNull(properties("   ").getParsedChatTemplateKwargs());
        assertNull(properties("{}").getParsedChatTemplateKwargs());
    }

    @Test
    void malformedJsonFailsAtStartup() {
        // A bare array or scalar is not a set of template switches.
        assertThrows(IllegalStateException.class, () -> properties("[1, 2]"));
        assertThrows(IllegalStateException.class, () -> properties("\"low\""));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> properties("{\"enable_thinking\": tru}"));
        assertTrue(error.getMessage().contains("openai.chat-template-kwargs"));
    }
}
