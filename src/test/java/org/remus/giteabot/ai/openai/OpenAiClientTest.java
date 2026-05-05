package org.remus.giteabot.ai.openai;

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

class OpenAiClientTest {

    private OpenAiClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new OpenAiClient(restClient, "gpt-4o", 1024, 10, 2, 6);
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
    void chat_withMcpConfiguration_forwardsMcpServers() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        ArgumentCaptor<OpenAiRequest> requestCaptor = ArgumentCaptor.forClass(OpenAiRequest.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/chat/completions")).thenReturn(bodySpec);
        when(bodySpec.body(requestCaptor.capture())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(OpenAiResponse.class)).thenReturn(response("ok"));
        OpenAiClient client = new OpenAiClient(restClient, "gpt-4o", 1024, 10, 2, 6);

        String result = client.chat(List.of(), "hello", "system", null, 100,
                new McpConfigurationData("GitHub MCP", """
                        {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                        """));

        assertEquals("ok", result);
        assertNotNull(requestCaptor.getValue().getMcpServers());
    }

    private OpenAiResponse response(String text) {
        OpenAiResponse response = new OpenAiResponse();
        OpenAiResponse.Message message = new OpenAiResponse.Message();
        message.setRole("assistant");
        message.setContent(text);
        OpenAiResponse.Choice choice = new OpenAiResponse.Choice();
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }
}
