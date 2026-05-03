package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.anthropic.AnthropicProviderMetadata;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppProviderMetadata;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.ollama.OllamaProviderMetadata;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiProviderMetadata;
import org.remus.giteabot.ai.vllm.VllmClient;
import org.remus.giteabot.ai.vllm.VllmProviderMetadata;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiClientFactoryTest {

    @Mock
    private AiIntegrationService aiIntegrationService;

    private AiProviderRegistry providerRegistry;
    private AiClientFactory aiClientFactory;

    @BeforeEach
    void setUp() {
        // Create real provider registry with all providers
        providerRegistry = new AiProviderRegistry(List.of(
                new AnthropicProviderMetadata(),
                new OpenAiProviderMetadata(),
                new OllamaProviderMetadata(),
                new LlamaCppProviderMetadata(),
                new VllmProviderMetadata()
        ));
        aiClientFactory = new AiClientFactory(aiIntegrationService, providerRegistry);
    }

    @Test
    void getClient_anthropic_createsAnthropicClient() {
        AiIntegration integration = createIntegration("anthropic", "sk-test");
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn("sk-test");

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(AnthropicAiClient.class, client);
    }

    @Test
    void getClient_openai_createsOpenAiClient() {
        AiIntegration integration = createIntegration("openai", "sk-test");
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn("sk-test");

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(OpenAiClient.class, client);
    }

    @Test
    void getClient_ollama_createsOllamaClient() {
        AiIntegration integration = createIntegration("ollama", null);
        // Ollama doesn't require API key, so decryptApiKey should not be called

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(OllamaClient.class, client);
        verify(aiIntegrationService, never()).decryptApiKey(any());
    }

    @Test
    void getClient_llamacpp_createsLlamaCppClient() {
        AiIntegration integration = createIntegration("llamacpp", null);
        // llamacpp doesn't require API key, so decryptApiKey should not be called

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(LlamaCppClient.class, client);
        verify(aiIntegrationService, never()).decryptApiKey(any());
    }

    @Test
    void getClient_vllm_createsVllmClientWithoutApiKey() {
        AiIntegration integration = createIntegration("vllm", null);
        // vLLM doesn't require API key, so decryptApiKey should not be called when unset

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(VllmClient.class, client);
        verify(aiIntegrationService, never()).decryptApiKey(any());
    }

    @Test
    void getClient_vllm_decryptsOptionalApiKeyWhenConfigured() {
        AiIntegration integration = createIntegration("vllm", "encrypted-token");
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn("test-token");

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(VllmClient.class, client);
        verify(aiIntegrationService).decryptApiKey(integration);
    }

    @Test
    void getClient_unknownProvider_throwsException() {
        AiIntegration integration = createIntegration("unknown", null);

        assertThrows(IllegalArgumentException.class, () -> aiClientFactory.getClient(integration));
    }

    @Test
    void getClient_cachesResult_returnsSameInstance() {
        AiIntegration integration = createIntegration("ollama", null);

        AiClient first = aiClientFactory.getClient(integration);
        AiClient second = aiClientFactory.getClient(integration);
        assertSame(first, second);
    }

    @Test
    void getClient_rebuildsClientWhenUpdatedAtChanges() {
        AiIntegration integration = createIntegration("ollama", null);

        AiClient first = aiClientFactory.getClient(integration);
        // Simulate configuration update
        integration.setUpdatedAt(Instant.now().plusSeconds(60));
        AiClient second = aiClientFactory.getClient(integration);
        assertNotSame(first, second);
    }

    private AiIntegration createIntegration(String providerType, String apiKey) {
        AiIntegration integration = new AiIntegration();
        integration.setId(1L);
        integration.setName("test-" + providerType);
        integration.setProviderType(providerType);
        integration.setApiUrl("http://localhost:8080");
        integration.setApiKey(apiKey);
        integration.setApiVersion("2023-06-01");
        integration.setModel("test-model");
        integration.setMaxTokens(4096);
        integration.setMaxDiffCharsPerChunk(120000);
        integration.setMaxDiffChunks(8);
        integration.setRetryTruncatedChunkChars(60000);
        integration.setUpdatedAt(Instant.now());
        return integration;
    }
}
