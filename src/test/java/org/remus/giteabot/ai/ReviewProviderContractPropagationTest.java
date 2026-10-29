package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.anthropic.AnthropicProviderMetadata;
import org.remus.giteabot.ai.google.GoogleAiProviderMetadata;
import org.remus.giteabot.ai.llamacpp.LlamaCppProviderMetadata;
import org.remus.giteabot.ai.ollama.OllamaProviderMetadata;
import org.remus.giteabot.ai.openai.OpenAiProviderMetadata;
import org.remus.giteabot.ai.openrouter.OpenRouterProviderMetadata;
import org.remus.giteabot.config.AiRetryProperties;
import org.remus.giteabot.config.AnthropicExtendedThinkingProperties;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLimits;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.remus.giteabot.ai.ReviewProviderContract.*;

class ReviewProviderContractPropagationTest {

    @Test
    void legacyMetadataAndDecoratedClientsRemainUnsupportedWithoutProviderCalls() {
        AiClient legacy = new LegacyClient();
        AiClient wrapped = new AuditingAiClient(new RetryAiClient(legacy,
                new AiRetryProperties(), null), null);

        assertSame(ReviewProviderContract.unsupported(), legacy.reviewProviderContract());
        assertSame(ReviewProviderContract.unsupported(), wrapped.reviewProviderContract());
        assertSame(ReviewProviderContract.unsupported(), new LegacyMetadata().reviewProviderContract(null));
        assertEquals(1, wrapped.reviewProviderContract().version());
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(new Binding(7, "fixture", "concrete-model-v1", "deployment-v1",
                        "policy-v1", "key-revision-1"), 4_096, 200_000, "provider-default"),
                wrapped.reviewProviderContract(), new AgentReviewLimits.RepositoryLimits(65_536, 1_000),
                null, null, Instant.parse("2026-10-18T12:00:00Z"), Instant.parse("2026-10-18T12:00:00Z"),
                AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void qualifiedEvidencePropagatesUnchangedInBothDecoratorOrdersAndFromMetadata() {
        var contract = new Qualified(1, new Binding(7, "fixture", "concrete-model-v1", "deployment-v1",
                "policy-v1", "key-revision-1"), new Evidence(ProofSource.ADAPTER_GUARANTEE,
                "fixture-contract", Instant.parse("2026-10-18T11:00:00Z"), "v1", "One generation; no hidden fallbacks"),
                InputPreservation.COMPLETE_OR_REJECT, new TotalTokens(200_000,
                new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS)), Set.of("provider-default"));
        AiClient client = new LegacyClient() {
            @Override public ReviewProviderContract reviewProviderContract() { return contract; }
        };
        AiProviderMetadata metadata = new LegacyMetadata() {
            @Override public ReviewProviderContract reviewProviderContract(AiIntegration integration) { return contract; }
        };
        var auditOutside = new AuditingAiClient(new RetryAiClient(client, new AiRetryProperties(), null), null);
        var retryOutside = new RetryAiClient(new AuditingAiClient(client, null), new AiRetryProperties(), null);

        assertSame(contract, auditOutside.reviewProviderContract());
        assertSame(contract, retryOutside.reviewProviderContract());
        assertSame(contract, metadata.reviewProviderContract(new AiIntegration()));
    }

    @Test
    void everyShippedAdapterAndItsNativeClientRemainUnsupported() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("restClientBuilder", RestClient.builder());
        var builders = beans.getBeanProvider(RestClient.Builder.class);
        var providers = List.of(new AnthropicProviderMetadata(builders, new AnthropicExtendedThinkingProperties()),
                new OpenAiProviderMetadata(builders), new GoogleAiProviderMetadata(builders),
                new OllamaProviderMetadata(builders), new LlamaCppProviderMetadata(builders),
                new OpenRouterProviderMetadata(builders, HttpClientSettings.defaults()));
        var integration = new AiIntegration();
        integration.setModel("fixture/concrete-model-v1");
        var transport = RestClient.builder().requestFactory((uri, method) -> {
            throw new AssertionError("Contract discovery must never call HTTP");
        }).build();
        for (AiProviderMetadata provider : providers) {
            assertSame(ReviewProviderContract.unsupported(), provider.reviewProviderContract(integration));
            assertSame(ReviewProviderContract.unsupported(), provider.createClient(transport, integration).reviewProviderContract());
        }
    }

    private static class LegacyClient implements AiClient {
        @Override public String submitReviewPrompt(String system, String model, String message) {
            throw new AssertionError("Preflight must not generate");
        }
        @Override public String chat(List<AiMessage> history, String message, String system, String model) {
            throw new AssertionError("Preflight must not generate");
        }
        @Override public String chat(List<AiMessage> history, String message, String system,
                                     String model, Integer maxTokens) {
            throw new AssertionError("Preflight must not generate");
        }
        @Override public void reportError(Throwable error) { throw new AssertionError("No audit effect expected"); }
        @Override public String getModel() { return "fixture-model"; }
    }

    private static class LegacyMetadata implements AiProviderMetadata {
        @Override public String getProviderType() { return "fixture"; }
        @Override public String getDefaultApiUrl() { return "https://fixture.invalid"; }
        @Override public List<String> getSuggestedModels() { return List.of(); }
        @Override public boolean requiresApiKey() { return false; }
        @Override public RestClient buildRestClient(AiIntegration integration, String key) {
            throw new AssertionError("Preflight must not create a transport");
        }
        @Override public AiClient createClient(RestClient transport, AiIntegration integration) {
            throw new AssertionError("Preflight must not create a client");
        }
    }
}
