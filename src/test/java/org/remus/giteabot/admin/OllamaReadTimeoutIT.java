package org.remus.giteabot.admin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.aiusage.AiUsageService;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@code spring.http.clients.read-timeout} actually applies to the
 * Ollama AI client built through {@link AiClientFactory}: a server that delays
 * its response beyond the configured timeout must produce a read-timeout error
 * (not just hang).
 */
@SpringBootTest
@TestPropertySource(properties = "spring.http.clients.read-timeout=2s")
class OllamaReadTimeoutIT {

    @Autowired
    private AiClientFactory aiClientFactory;

    @Autowired
    private AiProviderRegistry providerRegistry;

    @Autowired
    private AiIntegrationService aiIntegrationService;

    @Autowired
    private AiUsageService aiUsageService;

    @Autowired
    private AiUsageProperties usageProperties;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                // Delay well beyond the 2s read timeout before responding.
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ollamaClientHonoursConfiguredReadTimeout() {
        AiIntegration integration = new AiIntegration();
        integration.setId(987654L);
        integration.setName("timeout-test");
        integration.setProviderType("ollama");
        integration.setApiUrl("http://localhost:" + server.getAddress().getPort());
        integration.setModel("test-model");
        integration.setMaxTokens(128);
        integration.setCreatedAt(java.time.Instant.now());
        integration.setUpdatedAt(java.time.Instant.now());

        AiClientFactory factory = new AiClientFactory(aiIntegrationService, providerRegistry,
                aiUsageService, usageProperties);

        long start = System.nanoTime();
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> factory.getClient(integration).submitReviewPrompt("system", null, "user"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 9_000,
                "request should have timed out around 2s, took " + elapsedMillis + "ms");
        assertTrue(String.valueOf(ex.getMessage()).toLowerCase().contains("timed out")
                        || ex.getCause() instanceof java.net.SocketTimeoutException,
                "expected a read timeout, got: " + ex);
    }
}
