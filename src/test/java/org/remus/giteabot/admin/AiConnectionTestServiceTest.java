package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiConnectionTestService.Result;
import org.remus.giteabot.admin.AiConnectionTestService.Status;
import org.remus.giteabot.admin.AiConnectionTestService.Step;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.node.JsonNodeFactory;

import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.client5.http.ConnectTimeoutException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConnectionTestServiceTest {

    private AiIntegrationService integrationService;
    private AiProviderMetadata provider;
    private AiClient client;
    private AiConnectionTestService service;

    @BeforeEach
    void setUp() {
        integrationService = mock(AiIntegrationService.class);
        AiProviderRegistry registry = mock(AiProviderRegistry.class);
        provider = mock(AiProviderMetadata.class);
        client = mock(AiClient.class);
        when(registry.getProviderOrThrow(any())).thenReturn(provider);
        when(provider.requiresApiKey()).thenReturn(true);
        when(provider.buildRestClient(any(), any())).thenReturn(mock(RestClient.class));
        when(provider.createClient(any(), any())).thenReturn(client);
        when(client.supportsNativeTools()).thenReturn(true);
        // The key rules are the real ones; only the lookup and decryption are stubbed.
        when(integrationService.findRetainedKeyOwner(any(), anyBoolean())).thenCallRealMethod();
        service = new AiConnectionTestService(integrationService, registry,
                new AiClientFactory(integrationService, registry, null, null, null, null));
    }

    @Test
    void reportsReplyAndSuccessfulToolCall() {
        answerConnection(ChatTurn.text("yes"));
        answerTools(new ChatTurn("", List.of(new ToolCall("1", AiConnectionTestService.PROBE_TOOL_NAME,
                JsonNodeFactory.instance.objectNode().put("status", "ok"))), StopReason.TOOL_USE, 0, 0));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(0), "connection", Status.OK, "aiTest.connection.ok");
        assertThat(result.steps().get(0).args()).containsExactly("yes");
        assertStep(result.steps().get(1), "toolCalling", Status.OK, "aiTest.tools.ok");
        verify(client).chatWithTools(anyList(), eq(AiConnectionTestService.TOOL_PROMPT),
                argThat(tools -> tools.size() == 1
                        && tools.getFirst().name().equals(AiConnectionTestService.PROBE_TOOL_NAME)),
                any(), isNull(), isNull());
    }

    @Test
    void warnsWhenModelAnswersWithTextInsteadOfCallingTheTool() {
        answerConnection(ChatTurn.text("yes"));
        answerTools(ChatTurn.text("ok"));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(1), "toolCalling", Status.WARNING, "aiTest.tools.notCalled");
    }

    @Test
    void reportsRejectedToolRequest() {
        answerConnection(ChatTurn.text("yes"));
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.TOOL_PROMPT), anyList(), any(), any(), any()))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(1), "toolCalling", Status.FAILED, "aiTest.tools.rejected");
        assertThat(result.steps().get(1).args()).containsExactly(400);
        assertThat(result.steps().get(1).detail()).isEqualTo("details");
    }

    @Test
    void passesOnTheProvidersErrorMessage() {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.CONNECTION_PROMPT), anyList(), any(), any(), any()))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST,
                        "{\"error\":{\"message\":\"max_tokens is too large: 99999\",\"type\":\"invalid_request_error\"}}"));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(0), "connection", Status.FAILED, "aiTest.error.http");
        assertThat(result.steps().get(0).detail()).isEqualTo("max_tokens is too large: 99999");
    }

    @Test
    void extractsTheMessageFromCommonErrorBodies() {
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_REQUEST,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"model: unknown\"}}")))
                .isEqualTo("model: unknown");
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.NOT_FOUND,
                "{\"error\":\"model 'llama9' not found\"}")))
                .isEqualTo("model 'llama9' not found");
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_REQUEST,
                "{\"object\":\"error\",\"message\":\"tools are not supported\"}")))
                .isEqualTo("tools are not supported");
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_REQUEST, "{\"code\":17}")))
                .isEqualTo("{\"code\":17}");
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_REQUEST, "plain text reason")))
                .isEqualTo("plain text reason");
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_GATEWAY,
                "<html><body>Bad gateway</body></html>"))).isNull();
        assertThat(AiConnectionTestService.providerMessage(httpError(HttpStatus.BAD_REQUEST, ""))).isNull();
    }

    @Test
    void skipsToolProbeWhenNativeToolCallingIsTurnedOff() {
        when(client.supportsNativeTools()).thenReturn(false);
        answerConnection(ChatTurn.text("yes"));
        AiIntegration integration = integration();
        integration.setEnableNativeToolCalling(false);

        Result result = service.test(integration, "sk-test", false);

        assertStep(result.steps().get(1), "toolCalling", Status.SKIPPED, "aiTest.tools.disabled");
        verify(client, never()).chatWithTools(anyList(), eq(AiConnectionTestService.TOOL_PROMPT),
                anyList(), any(), any(), any());
    }

    @Test
    void skipsToolProbeForProvidersWithoutNativeTools() {
        when(client.supportsNativeTools()).thenReturn(false);
        answerConnection(ChatTurn.text("yes"));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(1), "toolCalling", Status.SKIPPED, "aiTest.tools.unsupportedProvider");
    }

    @Test
    void warnsWhenReasoningUsesUpTheTokenBudget() {
        answerConnection(new ChatTurn("", List.of(), StopReason.MAX_TOKENS, 10, 4096));
        answerTools(ChatTurn.text("ok"));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(0), "connection", Status.WARNING, "aiTest.connection.emptyMaxTokens");
    }

    @Test
    void mapsUnauthorizedAndSkipsToolProbe() {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.CONNECTION_PROMPT), anyList(), any(), any(), any()))
                .thenThrow(httpError(HttpStatus.UNAUTHORIZED));

        Result result = service.test(integration(), "sk-test", false);

        assertStep(result.steps().get(0), "connection", Status.FAILED, "aiTest.error.unauthorized");
        assertStep(result.steps().get(1), "toolCalling", Status.SKIPPED, "aiTest.tools.skippedAfterFailure");
    }

    @Test
    void mapsTransportFailuresToTheirLikelyCause() {
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new UnknownHostException("llm.invalid"))).messageKey())
                .isEqualTo("aiTest.error.unknownHost");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new SSLHandshakeException("PKIX path building failed"))).messageKey())
                .isEqualTo("aiTest.error.tls");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new ConnectException("Connection refused"))).messageKey())
                .isEqualTo("aiTest.error.connectionRefused");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new ConnectTimeoutException("Connect to llm:443 failed"))).messageKey())
                .isEqualTo("aiTest.error.connectTimeout");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Connect timed out"))).messageKey())
                .isEqualTo("aiTest.error.connectTimeout");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))).messageKey())
                .isEqualTo("aiTest.error.readTimeout");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new HttpConnectTimeoutException("HTTP connect timed out"))).messageKey())
                .isEqualTo("aiTest.error.connectTimeout");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out"))).messageKey())
                .isEqualTo("aiTest.error.readTimeout");
        assertThat(AiConnectionTestService.describeFailure("connection",
                httpError(HttpStatus.NOT_FOUND)).messageKey())
                .isEqualTo("aiTest.error.notFound");
        assertThat(AiConnectionTestService.describeFailure("connection",
                new IllegalStateException("boom")).messageKey())
                .isEqualTo("aiTest.error.unexpected");
    }

    @Test
    void usesStoredKeyWhenFieldIsLeftBlankOnEdit() {
        AiIntegration stored = integration();
        stored.setId(7L);
        stored.setApiKey("ciphertext");
        when(integrationService.findById(7L)).thenReturn(Optional.of(stored));
        when(integrationService.decryptApiKey(stored)).thenReturn("sk-stored");
        answerConnection(ChatTurn.text("yes"));
        answerTools(ChatTurn.text("ok"));
        AiIntegration form = integration();
        form.setId(7L);

        service.test(form, "", false);

        verify(provider).buildRestClient(any(), eq("sk-stored"));
    }

    @Test
    void requiresNewKeyWhenProviderChanged() {
        AiIntegration stored = integration();
        stored.setId(7L);
        stored.setProviderType(AiProviderType.ANTHROPIC);
        when(integrationService.findById(7L)).thenReturn(Optional.of(stored));
        AiIntegration form = integration();
        form.setId(7L);

        Result result = service.test(form, null, false);

        assertStep(result.steps().get(0), "connection", Status.FAILED, "aiTest.error.providerChangedNeedsKey");
        verify(integrationService, never()).decryptApiKey(any());
        verify(provider, never()).buildRestClient(any(), any());
    }

    @Test
    void requiresKeyForProvidersThatNeedOne() {
        Result result = service.test(integration(), " ", false);

        assertStep(result.steps().get(0), "connection", Status.FAILED, "aiTest.error.apiKeyMissing");
        verify(provider, never()).buildRestClient(any(), any());
    }

    @Test
    void clearedKeyIsNotReplacedByTheStoredOne() {
        AiIntegration form = integration();
        form.setId(7L);

        Result result = service.test(form, null, true);

        assertStep(result.steps().get(0), "connection", Status.FAILED, "aiTest.error.apiKeyMissing");
        verify(integrationService, never()).findById(any());
    }

    @Test
    void neverEchoesTheApiKey() {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.CONNECTION_PROMPT), anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("bad request for key sk-secret"));

        Result result = service.test(integration(), "sk-secret", false);

        assertThat(result.steps().get(0).args()).containsExactly("bad request for key ***");
    }

    @Test
    void redactsTheApiKeyFromTheProvidersErrorMessage() {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.CONNECTION_PROMPT), anyList(), any(), any(), any()))
                .thenThrow(httpError(HttpStatus.UNAUTHORIZED,
                        "{\"error\":{\"message\":\"Incorrect API key provided: sk-secret\"}}"));

        Result result = service.test(integration(), "sk-secret", false);

        assertThat(result.steps().get(0).detail()).isEqualTo("Incorrect API key provided: ***");
    }

    private void answerConnection(ChatTurn turn) {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.CONNECTION_PROMPT), anyList(), any(), any(), any()))
                .thenReturn(turn);
    }

    private void answerTools(ChatTurn turn) {
        when(client.chatWithTools(anyList(), eq(AiConnectionTestService.TOOL_PROMPT), anyList(), any(), any(), any()))
                .thenReturn(turn);
    }

    private static void assertStep(Step step, String name, Status status, String messageKey) {
        assertThat(step.name()).isEqualTo(name);
        assertThat(step.status()).isEqualTo(status);
        assertThat(step.messageKey()).isEqualTo(messageKey);
    }

    private static RestClientResponseException httpError(HttpStatus status) {
        return httpError(status, "{\"error\":\"details\"}");
    }

    private static RestClientResponseException httpError(HttpStatus status, String body) {
        return new RestClientResponseException(status.getReasonPhrase(), status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static AiIntegration integration() {
        AiIntegration integration = new AiIntegration();
        integration.setName("test");
        integration.setProviderType(AiProviderType.OPENAI);
        integration.setApiUrl("https://api.openai.com");
        integration.setModel("gpt-test");
        return integration;
    }
}
