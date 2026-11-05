package org.remus.giteabot.ai.openai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiClientTest {

    @ParameterizedTest
    @MethodSource("cacheUsageCases")
    void extractText_recordsProviderCacheUsage(String details, long cacheWrite, long cacheRead) {
        OpenAiClient client = createClient();
        AiAuditRecorder recorder = mock(AiAuditRecorder.class);
        client.setAuditRecorder(recorder);
        OpenAiResponse response = cacheUsageResponse(details);

        assertEquals("ok", client.extractText(reviewRequest(), response, "review"));

        verify(recorder).recordUsage(140699L, 314L, cacheWrite, cacheRead, null, null);
    }

    @ParameterizedTest
    @MethodSource("cacheUsageCases")
    void chatWithTools_recordsProviderCacheUsage(String details, long cacheWrite, long cacheRead) {
        OpenAiResponse response = cacheUsageResponse(details);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/chat/completions")).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(OpenAiResponse.class)).thenReturn(response);
        OpenAiClient client = new OpenAiClient(restClient, "gpt-6.1-sol", 1024, true, OpenAiFlavor.STANDARD, null);
        AiAuditRecorder recorder = mock(AiAuditRecorder.class);
        client.setAuditRecorder(recorder);
        org.remus.giteabot.ai.ToolDescriptor tool = new org.remus.giteabot.ai.ToolDescriptor(
                "search", "Search the codebase.", AgentJackson.mapper().createObjectNode());

        ChatTurn turn = client.chatWithTools(List.of(), "review this PR", List.of(tool),
                "You are a reviewer.", null, null);

        assertEquals("ok", turn.assistantText());
        assertEquals(140699L, turn.inputTokens());
        assertEquals(314L, turn.outputTokens());
        verify(recorder).recordUsage(140699L, 314L, cacheWrite, cacheRead, null, null);
    }

    static Stream<Arguments> cacheUsageCases() {
        return Stream.of(
                Arguments.of("", 0L, 0L),
                Arguments.of(",\"prompt_tokens_details\":null", 0L, 0L),
                Arguments.of(",\"prompt_tokens_details\":{\"cached_tokens\":null,\"cache_write_tokens\":null}", 0L, 0L),
                Arguments.of(",\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}", 0L, 0L),
                Arguments.of(",\"prompt_tokens_details\":{\"cached_tokens\":120000}", 0L, 120000L),
                Arguments.of(",\"prompt_tokens_details\":{\"cache_write_tokens\":4096}", 4096L, 0L),
                Arguments.of(",\"prompt_tokens_details\":{\"cached_tokens\":120000,\"cache_write_tokens\":4096}",
                        4096L, 120000L));
    }

    private static OpenAiResponse cacheUsageResponse(String details) {
        return AgentJackson.mapper().readValue("""
                {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":140699,"completion_tokens":314,"total_tokens":141013%s}}
                """.formatted(details), OpenAiResponse.class);
    }

    private OpenAiClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new OpenAiClient(restClient, "gpt-4o", 1024, true, OpenAiFlavor.STANDARD, null);
    }

    @Test
    void isPromptTooLongError_detectsContextLengthError() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens.\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTooManyTokensError() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"too many tokens in the request\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void supportsNativeTools_defaultsToTrue() {
        OpenAiClient client = createClient();
        assertTrue(client.supportsNativeTools());
    }

    @Test
    void supportsNativeTools_canBeDisabled() {
        OpenAiClient client = new OpenAiClient(mock(RestClient.class), "gpt-4o", 1024, false, OpenAiFlavor.STANDARD, null);
        assertFalse(client.supportsNativeTools());
    }

    @Test
    void extractText_explainsTokenBudget_whenFinishReasonIsLength() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "length"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("finish_reason=length"));
        assertTrue(result.contains("max tokens"));
        assertFalse(result.contains("empty response from AI"));
    }

    @Test
    void extractText_explainsTokenBudget_whenContentIsBlankAndFinishReasonIsLength() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice("", "length"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("finish_reason=length"));
    }

    @Test
    void extractText_keepsGenericMessage_whenNoLengthFinishReason() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "stop"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("empty response from AI"));
        assertFalse(result.contains("finish_reason=length"));
    }

    @Test
    void extractText_returnsContent_whenPresent() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice("The review looks good.", "stop"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("The review looks good."));
    }

    @Test
    void extractText_reportsProviderError_whenFinishReasonIsError() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "error"));
        OpenAiResponse.Error error = new OpenAiResponse.Error();
        error.setCode(502);
        error.setMessage("Provider disconnected mid-stream");
        response.getChoices().getFirst().setError(error);

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("provider returned an error"));
        assertTrue(result.contains("Provider disconnected mid-stream"));
        assertFalse(result.contains("empty response from AI"));
    }

    @Test
    void extractText_fallsBackToResponseError_whenChoiceErrorMissing() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "error"));
        OpenAiResponse.Error error = new OpenAiResponse.Error();
        error.setMessage("Rate limit exceeded");
        response.setError(error);

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("Rate limit exceeded"));
    }

    @Test
    void extractText_usesUnknownProviderError_whenErrorDetailsMissing() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "error"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("unknown provider error"));
    }

    @Test
    void errorField_isSerializedInRawResponseAuditPayload() {
        OpenAiResponse response = responseWith(choice(null, "error"));
        OpenAiResponse.Error error = new OpenAiResponse.Error();
        error.setCode(429);
        error.setMessage("Rate limit exceeded");
        response.getChoices().getFirst().setError(error);

        String json = org.remus.giteabot.agent.shared.AgentJackson.mapper().writeValueAsString(response);

        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"code\":429"));
        assertTrue(json.contains("Rate limit exceeded"));
    }

    @Test
    void chatWithTools_sendsReasoningEffortNone_whenNoReasoningFlavorSelected() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/chat/completions")).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        OpenAiResponse okResponse = new OpenAiResponse();
        OpenAiResponse.Choice choice = new OpenAiResponse.Choice();
        OpenAiResponse.Message message = new OpenAiResponse.Message();
        message.setContent("ok");
        choice.setMessage(message);
        choice.setFinishReason("stop");
        okResponse.setChoices(java.util.List.of(choice));
        when(responseSpec.body(OpenAiResponse.class)).thenReturn(okResponse);

        OpenAiClient client = new OpenAiClient(restClient, "gpt-6-luna", 1024, true, OpenAiFlavor.NO_REASONING, Collections.emptyMap());
        org.remus.giteabot.ai.ToolDescriptor tool =
                new org.remus.giteabot.ai.ToolDescriptor("search", "Search the codebase.",
                        new tools.jackson.databind.ObjectMapper().createObjectNode());

        ChatTurn turn = client.chatWithTools(java.util.List.of(), "review this PR",
                java.util.List.of(tool), "You are a reviewer.", null, null);

        org.mockito.ArgumentCaptor<OpenAiRequest> captor =
                org.mockito.ArgumentCaptor.forClass(OpenAiRequest.class);
        org.mockito.Mockito.verify(requestBodySpec, org.mockito.Mockito.times(1)).body(captor.capture());
        // The flavor value is written into the request body up front.
        assertEquals("none", chatTurnRequest(OpenAiFlavor.NO_REASONING, null).getReasoningEffort());
    }

    @Test
    void chatWithTools_omitsReasoningEffort_whenStandardFlavorSelected() {
        // Standard behavior keeps the payload untouched (fields omitted from JSON).
        assertNull(chatTurnRequest(OpenAiFlavor.STANDARD, null).getReasoningEffort());
    }

    @Test
    void chatWithTools_sendsChatTemplateKwargs_whenIntegrationProvidesThem() {
        // The switches are the integration's JSON, so keys and value types are
        // whatever the served model's template expects - here a Qwen-style string.
        OpenAiRequest request = chatTurnRequest(OpenAiFlavor.STANDARD,
                Map.of("enable_thinking", true, "reasoning_effort", "low"));

        assertEquals(Map.of("enable_thinking", true, "reasoning_effort", "low"),
                request.getChatTemplateKwargs());
        String json = AgentJackson.mapper().writeValueAsString(request);
        assertTrue(json.contains("\"chat_template_kwargs\""));
        assertTrue(json.contains("\"enable_thinking\":true"));
        assertTrue(json.contains("\"reasoning_effort\":\"low\""));
    }

    @Test
    void chatWithTools_keepsNumericAndNestedChatTemplateKwargsVerbatim() {
        // A template that expects a number must get 2, not "2".
        Map<String, Object> kwargs = Map.of(
                "reasoning_effort", 2,
                "thinking", Map.of("budget", 1024));
        OpenAiRequest request = chatTurnRequest(OpenAiFlavor.STANDARD, kwargs);

        assertEquals(kwargs, request.getChatTemplateKwargs());
        String json = AgentJackson.mapper().writeValueAsString(request);
        assertTrue(json.contains("\"reasoning_effort\":2"));
        assertTrue(json.contains("\"budget\":1024"));
    }

    @Test
    void chatWithTools_omitsChatTemplateKwargs_whenIntegrationSetsNone() {
        // A null map must not serialise as an empty object: the provider default
        // (the vLLM server's --default-chat-template-kwargs) applies instead.
        String json = AgentJackson.mapper().writeValueAsString(
                chatTurnRequest(OpenAiFlavor.STANDARD, null));
        assertFalse(json.contains("chat_template_kwargs"));
    }

    /** Runs one tool-enabled chat turn and returns the request body the client built. */
    private OpenAiRequest chatTurnRequest(OpenAiFlavor flavor, Map<String, Object> chatTemplateKwargs) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/chat/completions")).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(responseSpec.body(OpenAiResponse.class)).thenReturn(responseWith(choice("ok", "stop")));

        OpenAiClient client = new OpenAiClient(restClient, "gpt-6-luna", 1024, true,
                flavor, chatTemplateKwargs);
        ToolDescriptor tool = new ToolDescriptor("search", "Search the codebase.",
                new tools.jackson.databind.ObjectMapper().createObjectNode());
        ChatTurn turn = client.chatWithTools(List.of(), "review this PR",
                List.of(tool), "You are a reviewer.", null, null);
        assertEquals(StopReason.END_TURN, turn.stopReason());

        ArgumentCaptor<OpenAiRequest> captor = ArgumentCaptor.forClass(OpenAiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        return captor.getValue();
    }

    @Test
    void flavorFromId_resolvesKnownIds_andFallsBackToStandard() {
        assertEquals(OpenAiFlavor.STANDARD, OpenAiFlavor.fromId("standard"));
        assertEquals(OpenAiFlavor.NO_REASONING, OpenAiFlavor.fromId("no_reasoning"));
        assertEquals(OpenAiFlavor.NO_REASONING, OpenAiFlavor.fromId("  No_Reasoning "));
        assertEquals(OpenAiFlavor.STANDARD, OpenAiFlavor.fromId("bogus"));
        assertEquals(OpenAiFlavor.STANDARD, OpenAiFlavor.fromId(null));
    }

    @Test
    void modelFlavorsExposeStandardFirst_withProviderType() {
        java.util.List<org.remus.giteabot.ai.ModelFlavor> flavors =
                OpenAiFlavor.modelFlavors(org.remus.giteabot.ai.openai.OpenAiProviderMetadata.PROVIDER_TYPE);
        assertEquals(2, flavors.size());
        assertEquals("standard", flavors.get(0).id());
        assertEquals("no_reasoning", flavors.get(1).id());
        // Every flavor carries its provider type so ids are globally unique.
        flavors.forEach(flavor -> assertEquals(AiProviderType.OPENAI, flavor.providerType()));
        assertEquals("none", OpenAiFlavor.NO_REASONING.reasoningEffort());
        assertNull(OpenAiFlavor.STANDARD.reasoningEffort());
    }

    private OpenAiRequest reviewRequest() {
        // Only forwarded to usage reporting; extractText never reads it.
        return OpenAiRequest.builder()
                .model("test-model")
                .maxTokens(1024)
                .build();
    }

    private OpenAiResponse responseWith(OpenAiResponse.Choice... choices) {
        OpenAiResponse response = new OpenAiResponse();
        response.setChoices(java.util.Arrays.asList(choices));
        return response;
    }

    private OpenAiResponse.Choice choice(String content, String finishReason) {
        OpenAiResponse.Choice choice = new OpenAiResponse.Choice();
        OpenAiResponse.Message message = new OpenAiResponse.Message();
        message.setContent(content);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);
        return choice;
    }

}
