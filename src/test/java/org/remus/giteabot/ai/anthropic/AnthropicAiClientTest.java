package org.remus.giteabot.ai.anthropic;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.ai.McpConfigurationData;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnthropicAiClientTest {

    private AnthropicAiClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new AnthropicAiClient(restClient, "claude-sonnet-4-20250514", 1024,
                10, 2, 6);
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
    void chat_withMcpConfiguration_forwardsMcpServers() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        AnthropicResponse response = response("ok");
        ArgumentCaptor<AnthropicRequest> requestCaptor = ArgumentCaptor.forClass(AnthropicRequest.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/messages")).thenReturn(bodySpec);
        when(bodySpec.body(requestCaptor.capture())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(AnthropicResponse.class)).thenReturn(response);
        AnthropicAiClient client = new AnthropicAiClient(restClient, "claude-sonnet-4-20250514",
                1024, 10, 2, 6);

        String result = client.chat(List.of(), "hello", "system", null, 100,
                new McpConfigurationData("GitHub MCP", """
                        {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                        """));

        assertEquals("ok", result);
        assertNotNull(requestCaptor.getValue().getMcpServers());
    }

    private AnthropicResponse response(String text) {
        AnthropicResponse response = new AnthropicResponse();
        AnthropicResponse.ContentBlock block = new AnthropicResponse.ContentBlock();
        block.setType("text");
        block.setText(text);
        response.setContent(List.of(block));
        return response;
    }
}
