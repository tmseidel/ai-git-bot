package org.remus.giteabot.integration;

import org.junit.jupiter.api.*;
import org.remus.giteabot.admin.*;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test that starts the full Spring Boot application with mocked Gitea and AI HTTP servers.
 * Tests the per-bot webhook flow: create Bot + AiIntegration + GitIntegration in DB,
 * then call /api/webhook/{secret} and verify the review is posted via the bot's Gitea client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookIntegrationTest {

    static HttpServer giteaServer;
    static HttpServer anthropicServer;
    static int giteaPort;
    static int anthropicPort;

    static final List<String> giteaReviewBodies = Collections.synchronizedList(new ArrayList<>());
    static CountDownLatch reviewPostedLatch;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiIntegrationService aiIntegrationService;

    @Autowired
    private GitIntegrationService gitIntegrationService;

    @Autowired
    private BotService botService;

    @Autowired
    private SystemPromptService systemPromptService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        giteaServer = HttpServer.create(new InetSocketAddress(0), 0);
        anthropicServer = HttpServer.create(new InetSocketAddress(0), 0);

        giteaPort = giteaServer.getAddress().getPort();
        anthropicPort = anthropicServer.getAddress().getPort();

        setupGiteaServer();
        setupAnthropicServer();

        giteaServer.start();
        anthropicServer.start();
    }

    @BeforeEach
    void setUp() {
        giteaReviewBodies.clear();
        reviewPostedLatch = new CountDownLatch(1);
    }

    @AfterAll
    static void tearDown() {
        if (giteaServer != null) giteaServer.stop(0);
        if (anthropicServer != null) anthropicServer.stop(0);
    }

    private static void setupGiteaServer() {
        giteaServer.createContext("/api/v1/repos/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equals(method) && path.endsWith(".diff")) {
                String diff = generateDiff();
                byte[] response = diff.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } else if ("POST".equals(method) && path.endsWith("/reviews")) {
                String body = new String(exchange.getRequestBody().readAllBytes());
                giteaReviewBodies.add(body);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                reviewPostedLatch.countDown();
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
            }
        });
    }

    private static void setupAnthropicServer() {
        anthropicServer.createContext("/v1/messages", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
                return;
            }

            // consume request body
            exchange.getRequestBody().readAllBytes();

            String reviewText = "The code changes look reasonable. Good naming and structure.";
            String response = """
                    {
                        "id": "msg_test123",
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "text", "text": "%s"}],
                        "model": "claude-sonnet-4-20250514",
                        "stop_reason": "end_turn",
                        "usage": {"input_tokens": 500, "output_tokens": 100}
                    }
                    """.formatted(reviewText);

            byte[] responseBytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
    }

    @Test
    void perBotWebhook_triggersReview() throws Exception {
        // Set up integrations and bot in the DB
        AiIntegration ai = new AiIntegration();
        ai.setName("Test AI Integration");
        ai.setProviderType("anthropic");
        ai.setApiUrl("http://localhost:" + anthropicPort);
        ai.setApiKey("test-api-key");
        ai.setApiVersion("2023-06-01");
        ai.setModel("claude-sonnet-4-20250514");
        ai.setMaxTokens(1024);
        ai.setMaxDiffCharsPerChunk(50000);
        ai.setMaxDiffChunks(4);
        ai.setRetryTruncatedChunkChars(20000);
        ai = aiIntegrationService.save(ai);

        GitIntegration git = new GitIntegration();
        git.setName("Test Git Integration");
        git.setProviderType(org.remus.giteabot.repository.RepositoryType.GITEA);
        git.setUrl("http://localhost:" + giteaPort);
        git.setToken("test-gitea-token");
        git = gitIntegrationService.save(git);

        Bot bot = new Bot();
        bot.setName("Integration Test Bot");
        bot.setUsername("ai_bot");
        bot.setEnabled(true);
        bot.setAiIntegration(ai);
        bot.setGitIntegration(git);
        bot.setSystemPrompt(getOrCreateDefaultSystemPrompt());
        bot = botService.save(bot);

        String secret = bot.getWebhookSecret();
        String webhookPayload = createWebhookPayload("opened");

        long startTime = System.currentTimeMillis();
        mockMvc.perform(post("/api/webhook/" + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));
        long webhookResponseTime = System.currentTimeMillis() - startTime;

        assertTrue(webhookResponseTime < 2000, "Webhook should respond within 2 seconds, took " + webhookResponseTime + "ms");

        boolean posted = reviewPostedLatch.await(10, TimeUnit.SECONDS);
        assertTrue(posted, "Review should be posted within 10 seconds");
        assertFalse(giteaReviewBodies.isEmpty(), "A review should have been posted to Gitea");

        String reviewBody = giteaReviewBodies.getFirst();
        assertTrue(reviewBody.contains("body"), "Review should contain a body field");
    }

    @Test
    void perBotWebhook_unknownSecret_returns404() throws Exception {
        String webhookPayload = createWebhookPayload("opened");

        mockMvc.perform(post("/api/webhook/nonexistent-secret-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isNotFound());
    }

    @Test
    void perBotWebhook_closedPR_closesSession() throws Exception {
        AiIntegration ai = new AiIntegration();
        ai.setName("AI Close Test");
        ai.setProviderType("anthropic");
        ai.setApiUrl("http://localhost:" + anthropicPort);
        ai.setApiKey("test-api-key");
        ai.setApiVersion("2023-06-01");
        ai.setModel("claude-sonnet-4-20250514");
        ai.setMaxTokens(1024);
        ai.setMaxDiffCharsPerChunk(50000);
        ai.setMaxDiffChunks(4);
        ai.setRetryTruncatedChunkChars(20000);
        ai = aiIntegrationService.save(ai);

        GitIntegration git = new GitIntegration();
        git.setName("Git Close Test");
        git.setProviderType(org.remus.giteabot.repository.RepositoryType.GITEA);
        git.setUrl("http://localhost:" + giteaPort);
        git.setToken("test-token");
        git = gitIntegrationService.save(git);

        Bot bot = new Bot();
        bot.setName("Close Test Bot");
        bot.setUsername("ai_bot");
        bot.setEnabled(true);
        bot.setAiIntegration(ai);
        bot.setGitIntegration(git);
        bot.setSystemPrompt(getOrCreateDefaultSystemPrompt());
        bot = botService.save(bot);

        String webhookPayload = createWebhookPayload("closed");

        mockMvc.perform(post("/api/webhook/" + bot.getWebhookSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));
    }

    private String createWebhookPayload(String action) {
        return """
                {
                    "action": "%s",
                    "number": 42,
                    "pull_request": {
                        "id": 1,
                        "number": 42,
                        "title": "Add user authentication module",
                        "body": "This PR adds JWT-based authentication.",
                        "state": "open",
                        "user": {"login": "testowner"},
                        "requested_reviewers": [{"login": "ai_bot"}],
                        "head": {"ref": "feature/auth", "sha": "abc123"},
                        "base": {"ref": "main", "sha": "def456"}
                    },
                    "repository": {
                        "id": 1,
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """.formatted(action);
    }

    private static String generateDiff() {
        return """
                diff --git a/src/main/java/Example.java b/src/main/java/Example.java
                new file mode 100644
                index 0000000..1234567
                --- /dev/null
                +++ b/src/main/java/Example.java
                @@ -0,0 +1,10 @@
                +package com.example;
                +
                +public class Example {
                +    private String name;
                +
                +    public Example(String name) {
                +        this.name = name;
                +    }
                +
                +    public String getName() { return name; }
                +}
                """;
    }

    private SystemPrompt getOrCreateDefaultSystemPrompt() {
        return systemPromptService.findDefault().orElseGet(() -> {
            SystemPrompt systemPrompt = new SystemPrompt();
            systemPrompt.setName("Default Test");
            systemPrompt.setReviewSystemPrompt("Review test prompt");
            systemPrompt.setIssueAgentSystemPrompt("Agent test prompt");
            systemPrompt.setWriterAgentSystemPrompt("Writer test prompt");
            systemPrompt.setE2ePlannerSystemPrompt("Planner test prompt");
            systemPrompt.setE2eAuthorSystemPrompt("Author test prompt");
            systemPrompt.setE2eRunnerSystemPrompt("Runner test prompt");
            systemPrompt.setDefaultEntry(true);
            return systemPromptService.save(systemPrompt);
        });
    }
}
