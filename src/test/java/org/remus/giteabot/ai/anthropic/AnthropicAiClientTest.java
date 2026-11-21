package org.remus.giteabot.ai.anthropic;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnthropicAiClientTest {

    private AnthropicAiClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new AnthropicAiClient(restClient, "claude-sonnet-4-20250514", 1024, true, true, false, "high");
    }

    @Test
    void isPromptTooLongError_detectsError() {
        AnthropicAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"prompt is too long: 208154 tokens > 200000 maximum\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        AnthropicAiClient client = createClient();

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
        AnthropicAiClient client = createClient();
        assertTrue(client.supportsNativeTools());
    }

    @Test
    void supportsNativeTools_canBeDisabled() {
        AnthropicAiClient client = new AnthropicAiClient(mock(RestClient.class),
                "claude-sonnet-4-20250514", 1024, false, true, false, "high");
        assertFalse(client.supportsNativeTools());
    }

    @Test
    void chatWithTools_reportsTotalContextSizeAndRawCacheComponents() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/messages")).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        AnthropicResponse response = new AnthropicResponse();
        AnthropicResponse.ContentBlock text = new AnthropicResponse.ContentBlock();
        text.setType("text");
        text.setText("done");
        response.setContent(List.of(text));
        AnthropicResponse.Usage usage = new AnthropicResponse.Usage();
        usage.setInputTokens(300);
        usage.setOutputTokens(50);
        usage.setCacheCreationInputTokens(1200);
        usage.setCacheReadInputTokens(14500);
        response.setUsage(usage);
        when(responseSpec.body(AnthropicResponse.class)).thenReturn(response);

        AnthropicAiClient client = new AnthropicAiClient(restClient,
                "claude-sonnet-4-20250514", 1024, true, true, false, "high");
        AiUsageProperties usageProperties = new AiUsageProperties();
        usageProperties.setRawPayloadsEnabled(true);
        client.setUsageProperties(usageProperties);
        long[] recorded = new long[4];
        String[] recordedRaw = new String[2];
        client.setAuditRecorder(new AiAuditRecorder() {
            @Override
            public void recordUsage(long inputTokens, long outputTokens,
                                    long cacheCreationInputTokens, long cacheReadInputTokens,
                                    String rawRequest, String rawResponse) {
                recorded[0] = inputTokens;
                recorded[1] = outputTokens;
                recorded[2] = cacheCreationInputTokens;
                recorded[3] = cacheReadInputTokens;
                recordedRaw[0] = rawRequest;
                recordedRaw[1] = rawResponse;
            }

        });

        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ChatTurn turn = client.chatWithTools(List.of(), "hi",
                List.of(new ToolDescriptor("test_tool", "a test tool", schema)),
                "sys", null, null);

        // ChatTurn and the audit log both carry the total prompt size
        // (uncached + cache write + cache read), matching the provider
        // console's token totals; the cache fields carry the raw breakdown.
        assertEquals(16_000, turn.inputTokens(), "300 uncached + 1200 cache write + 14500 cache read");
        assertEquals(50, turn.outputTokens());
        assertArrayEquals(new long[]{16_000, 50, 1_200, 14_500}, recorded);
        assertNotNull(recordedRaw[0], "raw request should be serialized");
        assertTrue(recordedRaw[0].contains("test_tool"), "raw request should contain the tool");
        assertNotNull(recordedRaw[1], "raw response should be serialized");
        assertTrue(recordedRaw[1].contains("done"), "raw response should contain the assistant text");
    }

}
