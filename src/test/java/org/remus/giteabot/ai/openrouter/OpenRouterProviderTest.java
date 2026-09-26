package org.remus.giteabot.ai.openrouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ProviderRetryNotifier;
import org.remus.giteabot.ai.RetryAiClient;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.aiusage.AiUsageService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.AiRetryProperties;
import org.remus.giteabot.config.AiUsageProperties;
import org.remus.giteabot.prworkflow.agentreview.ReviewAgentStrategy;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class OpenRouterProviderTest {
    @Test
    void genericOpenAiKeepsItsWireContractEvenAtAnOpenRouterUrl() {
        RestClient.Builder http = RestClient.builder().baseUrl("https://openrouter.ai/api");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(jsonPath("$.max_completion_tokens").value(32))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.plugins").doesNotExist())
                .andExpect(jsonPath("$.messages[1].reasoning_details").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"Answer"}}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(http.build(), "author/model", 32, true, OpenAiFlavor.STANDARD);
        var previous = AiMessage.builder().role("assistant").content("Previous answer")
                .reasoningDetails(List.of(AgentJackson.mapper().readTree("{\"data\":\"opaque\"}"))).build();

        assertThat(client.chatWithTools(List.of(previous), "Continue", List.of(), "sys", null, null).assistantText())
                .isEqualTo("Answer");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Partial answer\"}}]}"})
    void stringOnlyApiRejectsIncompleteReplies(String response) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://openrouter.ai/api");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var client = new OpenRouterClient(http.build(), "author/model", 32, false);

        assertThatThrownBy(() -> client.chat(List.of(), "Question", "sys", null))
                .hasMessageContaining("incomplete text").hasMessageNotContaining("Partial answer");
        server.verify();
    }

    @Test
    void loopReplaysOpaqueReasoningDetailsWithTheToolHistory() {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andRespond(withSuccess("""
                            {"choices":[{"finish_reason":"tool_calls","message":{"content":"",
                             "reasoning_details":[{"type":"reasoning.encrypted","data":"signed-opaque-data","index":0},
                              {"type":"reasoning.text","text":"private-reasoning","format":"future-format","index":1}],
                             "tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":"{}"}}]}}],
                             "usage":{"prompt_tokens":100,"completion_tokens":12}}
                            """, MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(jsonPath("$.messages[2].reasoning_details[0].data").value("signed-opaque-data"))
                    .andExpect(jsonPath("$.messages[2].reasoning_details[0].type").value("reasoning.encrypted"))
                    .andExpect(jsonPath("$.messages[2].reasoning_details[0].index").value(0))
                    .andExpect(jsonPath("$.messages[2].reasoning_details[1].text").value("private-reasoning"))
                    .andExpect(jsonPath("$.messages[2].reasoning_details[1].format").value("future-format"))
                    .andExpect(jsonPath("$.messages[3].tool_call_id").value("call-1"))
                    .andRespond(withSuccess("""
                            {"choices":[{"finish_reason":"stop","message":{"content":"Final answer"}}]}
                            """, MediaType.APPLICATION_JSON));
            var provider = context.getBean(OpenRouterProviderMetadata.class);
            AiIntegration integration = new AiIntegration();
            integration.setModel("author/test-model");
            var client = provider.createClient(provider.buildRestClient(integration, "test-key"), integration);
            AiAuditRecorder audit = mock(AiAuditRecorder.class);
            ((AbstractAiClient) client).setAuditRecorder(audit);
            ((AbstractAiClient) client).setUsageProperties(new AiUsageProperties());
            AgentStrategy strategy = new AgentStrategy() {
                public String systemPrompt() { return "sys"; }
                public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
                public List<ToolDescriptor> toolDescriptors() { return List.of(new ToolDescriptor("lookup", "Read fixture", null)); }
                public StepDecision step(AgentRunContext ctx, String text, int round) { throw new AssertionError("Wrong handler"); }
                public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
                    return round == 1 ? new StepDecision.ContinueWithToolResults(
                            List.of(new StepDecision.ToolCallResult(turn.toolCalls().getFirst().id(), "fixture result")), null)
                            : new StepDecision.Finish(LoopOutcome.success("main", turn.assistantText()));
                }
                public LoopOutcome onBudgetExhausted(AgentRunContext ctx) { throw new AssertionError("Unexpected extra call"); }
            };
            var session = new AgentSession("owner", "repo", 1L, "test");
            session.setId(1L);
            var sessions = mock(AgentSessionRepository.class);
            when(sessions.getReferenceById(1L)).thenReturn(session);
            var run = new AgentRunContext(session, "owner", "repo", 1L, null, "main");
            var loop = new AgentLoop(client, new AgentSessionService(sessions),
                    new AgentBudget(2, 1, 1, 128, 8_000, 120_000, 200_000, 0.7));

            var outcome = loop.run(run, "Look up the fixture", strategy);

            assertThat(outcome.payload()).isEqualTo("Final answer");
            assertThat(session.getMessages()).allSatisfy(message ->
                    assertThat(message.getContent()).doesNotContain("signed-opaque-data", "private-reasoning"));
            verify(audit).recordUsage(100, 12, 0, 0, null, null);
            server.verify();
        }
    }

    @Test
    void registeredProviderUsesOfficialHostAndOpenRouterRequestDialect() {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                    .andExpect(jsonPath("$.max_tokens").value(32))
                    .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
                    .andExpect(jsonPath("$.provider.require_parameters").value(true))
                    .andExpect(jsonPath("$.provider.allow_fallbacks").value(false))
                    .andExpect(jsonPath("$.provider.data_collection").value("deny"))
                    .andExpect(jsonPath("$.provider.zdr").value(false))
                    .andExpect(content().json("""
                            {"plugins":[{"id":"web","enabled":false},{"id":"file-parser","enabled":false},
                             {"id":"response-healing","enabled":false},{"id":"context-compression","enabled":false},
                             {"id":"pareto-router","enabled":false}]}
                            """))
                    .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                    .andExpect(jsonPath("$.reasoning").doesNotExist())
                    .andExpect(jsonPath("$.tools[0].function.name").value("lookup"))
                    .andRespond(withSuccess("""
                            {"choices":[{"finish_reason":"tool_calls","message":{"content":"",
                             "tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":"{}"}}]}}],
                             "usage":{"prompt_tokens":100,"completion_tokens":12}}
                            """, MediaType.APPLICATION_JSON));
            AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
            assertThat(registry.getProviderTypes()).contains("openrouter");
            AiIntegration integration = new AiIntegration();
            integration.setId(1L);
            integration.setUpdatedAt(Instant.EPOCH);
            integration.setName("OpenRouter test");
            integration.setProviderType("openrouter");
            integration.setApiUrl("https://untrusted.example");
            integration.setModel("author/test-model");
            integration.setMaxTokens(32);
            AiIntegrationService integrations = mock(AiIntegrationService.class);
            when(integrations.decryptApiKey(integration)).thenReturn("test-key");
            var factory = new AiClientFactory(integrations, registry, mock(AiUsageService.class), new AiUsageProperties(),
                    new AiRetryProperties(), mock(ProviderRetryNotifier.class));

            var turn = factory.getClient(integration).chatWithTools(List.of(), "Look up the fixture",
                    List.of(new ToolDescriptor("lookup", "Read a fixture", null)), "sys", null, 32);

            assertThat(turn.stopReason()).isEqualTo(StopReason.TOOL_USE);
            assertThat(turn.toolCalls()).hasSize(1);
            assertThat(turn.inputTokens()).isEqualTo(100);
            assertThat(turn.outputTokens()).isEqualTo(12);
            server.verify();
        }
    }

    @Test
    void stringApiUsesOpenRouterCapsAndPolicy() {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            var server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(jsonPath("$.max_tokens").value(64))
                    .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
                    .andExpect(jsonPath("$.tools").doesNotExist())
                    .andExpect(jsonPath("$.provider.data_collection").value("deny"))
                    .andExpect(jsonPath("$.provider.zdr").value(false))
                    .andRespond(withSuccess("""
                            {"choices":[{"finish_reason":"stop","message":{"content":"Answer"}}]}
                            """, MediaType.APPLICATION_JSON));
            AiIntegration integration = new AiIntegration();
            integration.setModel("author/test-model");
            var provider = context.getBean(OpenRouterProviderMetadata.class);
            var client = provider.createClient(provider.buildRestClient(integration, "test-key"), integration);

            assertThat(client.chat(List.of(), "Question", "sys", null, 64)).isEqualTo("Answer");
            server.verify();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,length", "true,length", "false,unknown", "true,unknown", "false,empty", "true,empty"})
    void incompleteNativeAndLegacyRepliesCannotFinishTheReviewOrRunTools(boolean legacy, String stop) {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            var server = MockRestServiceServer.bindTo(http).build();
            String response = "empty".equals(stop) ? "{}" : """
                    {"choices":[{"finish_reason":"%s","message":{"content":"Partial finding",
                      "tool_calls":[{"id":"call-1","function":{"name":"pr-diff","arguments":"{}"}}]}}],
                      "usage":{"prompt_tokens":100,"completion_tokens":32}}
                    """.formatted(stop);
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(legacy ? jsonPath("$.tools").doesNotExist() : jsonPath("$.tools").isArray())
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            AiIntegration integration = new AiIntegration();
            integration.setModel("author/test-model");
            integration.setUseLegacyToolCalling(legacy);
            var provider = context.getBean(OpenRouterProviderMetadata.class);
            var client = provider.createClient(provider.buildRestClient(integration, "test-key"), integration);
            var router = mock(AgentToolRouter.class);
            var strategy = new ReviewAgentStrategy("sys", router, new ToolCatalog(new AgentConfigProperties()),
                    null, Set.of("pr-diff"), new AiResponseParser(), null, null, 1);
            var run = new AgentRunContext(new AgentSession("owner", "repo", 1L, "test"), "owner", "repo", 1L, null, "main");
            var loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                    new AgentBudget(2, 1, 1, 32, 8_000, 120_000, 200_000, 0.7));

            assertThat(loop.run(run, "Review the change", strategy).success()).isFalse();
            assertThat(run.toolingMode()).isEqualTo(legacy ? ToolingMode.LEGACY : ToolingMode.NATIVE);
            verifyNoInteractions(router);
            server.verify();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,network", "true,network", "false,body-network", "true,body-network", "false,context", "true,context",
            "false,embedded-overload", "true,embedded-overload", "false,http-overload", "true,http-overload"})
    void existingLoopAndOverloadRetriesStillRecognizeSanitizedFailures(boolean legacy, String failure) {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            var server = MockRestServiceServer.bindTo(http).build();
            ResponseCreator response = switch (failure) {
                case "network" -> withException(new SocketTimeoutException("private-provider-data"));
                case "body-network" -> request -> {
                    var interrupted = new MockClientHttpResponse(new InputStream() {
                        @Override public int read() throws SocketTimeoutException {
                            throw new SocketTimeoutException("private-provider-data");
                        }
                    }, HttpStatus.OK);
                    interrupted.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return interrupted;
                };
                case "context" -> withStatus(HttpStatus.BAD_REQUEST).body("maximum context length: private-provider-data");
                case "embedded-overload" -> withSuccess("""
                        {"choices":[{"finish_reason":"error","error":{"code":503,"message":"private-provider-data"}}]}
                        """, MediaType.APPLICATION_JSON);
                case "http-overload" -> withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("overloaded: private-provider-data");
                default -> throw new AssertionError(failure);
            };
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions")).andRespond(response);
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions")).andRespond(withSuccess("""
                    {"choices":[{"finish_reason":"stop","message":{"content":"Completed review"}}]}
                    """, MediaType.APPLICATION_JSON));
            AiIntegration integration = new AiIntegration();
            integration.setModel("author/test-model");
            integration.setUseLegacyToolCalling(legacy);
            var provider = context.getBean(OpenRouterProviderMetadata.class);
            var client = provider.createClient(provider.buildRestClient(integration, "test-key"), integration);
            var retries = new AiRetryProperties();
            retries.setMaxAttempts(2);
            retries.setInitialDelay(Duration.ZERO);
            var strategy = new ReviewAgentStrategy("sys", mock(AgentToolRouter.class), new ToolCatalog(new AgentConfigProperties()),
                    null, Set.of("pr-diff"), new AiResponseParser(), null, null, 1);
            var run = new AgentRunContext(new AgentSession("owner", "repo", 1L, "test"), "owner", "repo", 1L, null, "main");
            var loop = new AgentLoop(new RetryAiClient(client, retries, mock(ProviderRetryNotifier.class)),
                    new AgentSessionService(mock(AgentSessionRepository.class)),
                    new AgentBudget(2, 1, 1, 32, 8_000, 120_000, 200_000, 0.7));

            assertThat(loop.run(run, "Review the change", strategy).success()).isTrue();
            server.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 402, 404, 429, 503})
    void providerErrorsDoNotExposeResponseBodies(int status) {
        RestClient.Builder http = RestClient.builder();
        try (var context = providerContext(http)) {
            var server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"code\":400,\"message\":\"private-reasoning-and-key\"}}"));
            AiIntegration integration = new AiIntegration();
            integration.setModel("author/test-model");
            var provider = context.getBean(OpenRouterProviderMetadata.class);
            var client = provider.createClient(provider.buildRestClient(integration, "test-key"), integration);

            assertThatThrownBy(() -> client.chatWithTools(List.of(), "Review", List.of(), "sys", null, null))
                    .hasMessageContaining("OpenRouter").hasMessageNotContaining("private-reasoning-and-key").hasNoCause()
                    .satisfies(error -> {
                        if (error instanceof RestClientResponseException response) {
                            assertThat(response.getResponseBodyAsString()).doesNotContain("private-reasoning-and-key");
                            assertThat(response.getResponseHeaders()).isEqualTo(HttpHeaders.EMPTY);
                        }
                    });
            server.verify();
        }
    }

    private AnnotationConfigApplicationContext providerContext(RestClient.Builder http) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(RestClient.Builder.class, () -> http);
        context.registerBean(HttpClientSettings.class, HttpClientSettings::defaults);
        context.register(AiProviderRegistry.class);
        context.scan("org.remus.giteabot.ai.openrouter");
        context.refresh();
        return context;
    }
}
