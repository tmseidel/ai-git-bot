package org.remus.giteabot.ai.openai;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.OpenAiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wiring of the provider-level {@code chat_template_kwargs} setting into the
 * request body: the object is forwarded verbatim for the OpenAI-compatible
 * provider, and nothing is sent when the property is unset.
 */
class OpenAiProviderMetadataTest {

    private static final String OK = """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
            """;

    /**
     * The JSON is parsed (and validated) by {@code OpenAiProperties} at startup;
     * {@code OpenAiPropertiesTest} covers that. Here the parsed map is injected
     * directly, so this test only covers the provider-to-client wiring.
     */
    private static OpenAiProperties parsedProperties(java.util.Map<String, Object> kwargs) {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setParsedChatTemplateKwargs(kwargs);
        return properties;
    }

    private static AiIntegration integration() {
        AiIntegration integration = new AiIntegration();
        integration.setName("qwen-vllm");
        integration.setProviderType(OpenAiProviderMetadata.PROVIDER_TYPE);
        integration.setApiUrl("http://localhost:8000");
        integration.setModel("qwen3-35b-a3b");
        integration.setMaxTokens(8192);
        return integration;
    }

    private static AiClient clientFor(java.util.Map<String, Object> kwargs,
                                     MockRestServiceServer[] serverHolder) {
        RestClient.Builder http = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(http).build();
        ObjectProvider<RestClient.Builder> builders = new ObjectProvider<>() {
            @Override
            public RestClient.@NonNull Builder getObject() {
                return http;
            }
        };
        OpenAiProviderMetadata provider = new OpenAiProviderMetadata(builders, parsedProperties(kwargs));
        AiIntegration integration = integration();
        return provider.createClient(provider.buildRestClient(integration, "local"), integration);
    }

    @Test
    void forwardsChatTemplateKwargsVerbatim() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        AiClient client = clientFor(java.util.Map.of("enable_thinking", false, "reasoning_effort", 2), server);
        server[0].expect(requestTo("http://localhost:8000/v1/chat/completions"))
                .andExpect(jsonPath("$.chat_template_kwargs.enable_thinking").value(false))
                .andExpect(jsonPath("$.chat_template_kwargs.reasoning_effort").value(2))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.chatWithTools(List.of(), "review this PR", List.of(), "You are a reviewer.", null, null);

        server[0].verify();
    }

    @Test
    void omitsChatTemplateKwargsWhenPropertyIsUnset() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        AiClient client = clientFor(null, server);
        server[0].expect(requestTo("http://localhost:8000/v1/chat/completions"))
                .andExpect(jsonPath("$.chat_template_kwargs").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.chatWithTools(List.of(), "review this PR", List.of(), "You are a reviewer.", null, null);

        server[0].verify();
    }

    @Test
    void flavorsStayProviderShaped_onlyStandardAndNoReasoning() {
        OpenAiProperties properties = new OpenAiProperties();
        OpenAiProviderMetadata provider = new OpenAiProviderMetadata(new ObjectProvider<>() {
            @Override
            public RestClient.@NonNull Builder getObject() {
                return RestClient.builder();
            }
        }, properties);

        assertEquals(List.of("standard", "no_reasoning"),
                provider.getFlavors().stream().map(org.remus.giteabot.ai.ModelFlavor::id).toList());
        assertInstanceOf(OpenAiClient.class,
                provider.createClient(RestClient.builder().build(), integration()));
    }
}
