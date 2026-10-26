package org.remus.giteabot.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Request settings for the OpenAI-compatible provider ({@code openai}) — the
 * provider that talks to anything serving {@code /v1/chat/completions}, such as
 * vLLM, llama.cpp's server or a gateway.
 *
 * <p>{@code chat_template_kwargs} is an extension of those servers' request
 * body: vLLM hands the object to the model's Jinja chat template, where it
 * overrides the switches the process was started with
 * ({@code --default-chat-template-kwargs}). It is not part of the OpenAI API
 * and it is not a property of the bot's model abstraction, so it stays a
 * setting of this provider: no entity column, no admin-form field, no flavor.
 * Flavors describe a request *shape*; the reasoning vocabulary here belongs to
 * the template of whichever model the endpoint currently serves.
 *
 * <p>Configure with {@code openai.chat-template-kwargs} or the
 * {@code OPENAI_CHAT_TEMPLATE_KWARGS} environment variable. The translation
 * per model family is the operator's: Qwen-style templates take
 * {@code {"enable_thinking": true, "reasoning_effort": "low"}}, some take a
 * number, some only a boolean. Values keep their JSON type end to end.
 */
@Data
@Component
@ConfigurationProperties(prefix = "openai")
@Slf4j
public class OpenAiProperties {

    /** Own mapper: the config package must not depend on the agent package (ArchUnit). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Raw JSON object forwarded verbatim as {@code chat_template_kwargs} on
     * every chat-completions request. Empty (the default) sends nothing, so the
     * server's own defaults apply.
     */
    private String chatTemplateKwargs = "";

    /**
     * Parsed {@link #chatTemplateKwargs}; {@code null} when unset. Derived, not
     * a configuration key.
     */
    private Map<String, Object> parsedChatTemplateKwargs;

    @PostConstruct
    void parseChatTemplateKwargs() {
        String raw = getChatTemplateKwargs();
        if (raw == null || raw.isBlank()) {
            parsedChatTemplateKwargs = null;
            return;
        }
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            // Fail at startup rather than as a provider 400 on the first review:
            // the object is a startup setting, so a typo should never get that far.
            throw new IllegalStateException(
                    "openai.chat-template-kwargs must be a JSON object such as "
                            + "{\"enable_thinking\": true, \"reasoning_effort\": \"low\"}: "
                            + e.getMessage(), e);
        }
        parsedChatTemplateKwargs = parsed.isEmpty() ? null : parsed;
        log.info("OpenAI provider sends chat_template_kwargs {}", parsedChatTemplateKwargs);
    }
}
