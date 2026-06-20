package org.remus.giteabot.ai.google;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleAiClientTest {

    @Test
    void submitReviewPrompt_sendsGeminiRequestAndExtractsText() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader("x-goog-api-key", "secret-key")
                .defaultHeader("Content-Type", "application/json");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleAiClient client = new GoogleAiClient(builder.build(), "gemini-2.5-flash", 1024, true);

        server.expect(once(), requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "secret-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("System prompt"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(content().string(containsString("Please review this diff")))
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(1024))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{"content": {"parts": [{"text": "Looks good."}]}}],
                          "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 3, "totalTokenCount": 13}
                        }
                        """, MediaType.APPLICATION_JSON));

        String result = client.submitReviewPrompt("System prompt", null, "Please review this diff");

        assertEquals("Looks good.", result);
        server.verify();
    }

    @Test
    void chat_mapsAssistantRoleToGoogleModelRole() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleAiClient client = new GoogleAiClient(builder.build(), "models/gemini-2.5-pro", 2048, true);

        server.expect(once(), requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("Hi"))
                .andExpect(jsonPath("$.contents[1].role").value("model"))
                .andExpect(jsonPath("$.contents[1].parts[0].text").value("Hello"))
                .andExpect(jsonPath("$.contents[2].role").value("user"))
                .andExpect(jsonPath("$.contents[2].parts[0].text").value("Question"))
                .andRespond(withSuccess("""
                        {"candidates": [{"content": {"parts": [{"text": "Answer"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = client.chat(
                List.of(
                        AiMessage.builder().role("user").content("Hi").build(),
                        AiMessage.builder().role("assistant").content("Hello").build()
                ),
                "Question",
                "System",
                null);

        assertEquals("Answer", result);
        server.verify();
    }

    @Test
    void isPromptTooLongError_detectsGeminiTokenLimitError() {
        GoogleAiClient client = createClient();
        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"Input token count exceeds the maximum number of tokens\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void chat_wrapsInvalidKeyErrorWithClearMessage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleAiClient client = new GoogleAiClient(builder.build(), "gemini-2.5-flash", 1024, true);

        server.expect(once(), requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withBadRequest().body("""
                        {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}
                        """).contentType(MediaType.APPLICATION_JSON));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.chat(List.of(), "Question", "System", null));

        assertTrue(ex.getMessage().contains("Google AI request failed"));
        assertTrue(ex.getMessage().contains("API key not valid"));
        assertFalse(ex.getMessage().contains("secret"));
        server.verify();
    }

    private GoogleAiClient createClient() {
        return new GoogleAiClient(RestClient.builder().build(), "gemini-2.5-flash", 1024, true);
    }

    @Test
    void supportsNativeTools_defaultsToTrue() {
        assertTrue(createClient().supportsNativeTools());
    }

    @Test
    void supportsNativeTools_canBeDisabled() {
        GoogleAiClient client = new GoogleAiClient(RestClient.builder().build(),
                "gemini-2.5-flash", 1024,  false);
        assertFalse(client.supportsNativeTools());
    }
}
