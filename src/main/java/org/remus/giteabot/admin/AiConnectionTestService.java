package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.util.Throwables;
import org.remus.giteabot.ai.ToolDescriptor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Checks an AI integration as currently entered in the admin form, without
 * saving it. Two probes run against the provider:
 *
 * <ol>
 *   <li><b>Connection</b> — a tiny prompt. A reply proves that DNS, proxies,
 *       firewalls, TLS, the API key and the model name all work.</li>
 *   <li><b>Tool calling</b> — only when native tool calling is active for the
 *       integration: the model gets one trivial tool and is told to call it.
 *       The agent workflows depend on this, and a model or server that
 *       silently ignores tools is otherwise only noticed during a real run.</li>
 * </ol>
 *
 * <p>The client is built straight from the provider, without the retry and
 * audit wrappers that {@link AiClientFactory} adds: a test should fail fast
 * and must not appear in the usage statistics of an integration that may not
 * even exist yet.</p>
 *
 * <p>Results carry message keys instead of text; the controller localizes them.
 * An HTTP failure also carries the provider's own explanation as an untranslated
 * detail, because a 400 usually names the offending parameter. Only the message
 * field of the error body is passed on, shortened, and the API key is redacted
 * from every text the result holds.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConnectionTestService {

    static final String CONNECTION = "connection";
    static final String TOOL_CALLING = "toolCalling";
    static final String PROBE_TOOL_NAME = "connection_check";
    static final String CONNECTION_PROMPT = "This is a connectivity test. Answer with the single word: yes";
    static final String TOOL_PROMPT = "This is a tool-calling test. Call the tool \"" + PROBE_TOOL_NAME
            + "\" with status \"ok\". Do not answer with text.";
    private static final String SYSTEM_PROMPT = "You are answering an automated connectivity test. Follow the instructions exactly.";
    private static final int MAX_REPLY_CHARS = 200;
    private static final int MAX_DETAIL_CHARS = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiIntegrationService aiIntegrationService;
    private final AiProviderRegistry providerRegistry;
    private final AiClientFactory clientFactory;

    public enum Status { OK, WARNING, FAILED, SKIPPED }

    /**
     * One probe outcome; {@code messageKey} and {@code args} form a localizable
     * message. {@code detail} is the provider's own error text, or null.
     */
    public record Step(String name, Status status, String messageKey, List<Object> args, String detail) {
        public Step(String name, Status status, String messageKey, List<Object> args) {
            this(name, status, messageKey, args, null);
        }

        static Step of(String name, Status status, String messageKey, Object... args) {
            return new Step(name, status, messageKey, List.of(args));
        }

        Step withDetail(String detail) {
            return new Step(name, status, messageKey, args, detail);
        }
    }

    public record Result(List<Step> steps) {
        /** The form values could not even be turned into a client. */
        public static Result configurationFailure(String messageKey, Object... args) {
            return new Result(List.of(Step.of(CONNECTION, Status.FAILED, messageKey, args), skippedToolStep()));
        }
    }

    /**
     * Runs the probes for the integration as entered in the form.
     *
     * @param integration the unsaved form values; {@code id} is set when editing
     * @param newApiKey   the key typed into the form, blank to use the stored one
     * @param clearApiKey whether the operator asked to remove the stored key
     */
    public Result test(AiIntegration integration, String newApiKey, boolean clearApiKey) {
        String apiKey = null;
        try {
            apiKey = resolveApiKey(integration, newApiKey, clearApiKey);
            AiClient client = buildClient(integration, apiKey);
            Step connection = probeConnection(client);
            return redact(new Result(List.of(connection, probeToolCalling(client, integration, connection))), apiKey);
        } catch (ConfigurationProblem e) {
            return Result.configurationFailure(e.messageKey);
        } catch (RuntimeException e) {
            // Provider-side setting checks (e.g. OpenRouter's region) throw with a readable message.
            return redact(Result.configurationFailure("aiTest.error.configuration", safeMessage(e)), apiKey);
        }
    }

    /** Exception texts can echo request details; never hand the key back to the browser. */
    private static Result redact(Result result, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return result;
        }
        return new Result(result.steps().stream()
                .map(step -> new Step(step.name(), step.status(), step.messageKey(), step.args().stream()
                        .map(arg -> arg instanceof String text ? text.replace(apiKey, "***") : arg)
                        .toList(), step.detail() == null ? null : step.detail().replace(apiKey, "***")))
                .toList());
    }

    private AiClient buildClient(AiIntegration integration, String apiKey) {
        if (integration.getProviderType() == null) {
            throw new ConfigurationProblem("aiTest.error.providerMissing");
        }
        if (integration.getModel() == null || integration.getModel().isBlank()) {
            throw new ConfigurationProblem("aiTest.error.modelMissing");
        }
        AiProviderMetadata provider = providerRegistry.getProviderOrThrow(integration.getProviderType());
        provider.canonicalize(integration);
        if (integration.getApiUrl() == null || integration.getApiUrl().isBlank()) {
            throw new ConfigurationProblem("aiTest.error.apiUrlMissing");
        }
        if (provider.requiresApiKey() && (apiKey == null || apiKey.isBlank())) {
            throw new ConfigurationProblem("aiTest.error.apiKeyMissing");
        }
        return clientFactory.createUnwrappedClient(provider, integration, apiKey);
    }

    /** Same rules as saving: blank keeps the stored key, but only for the same provider. */
    private String resolveApiKey(AiIntegration integration, String newApiKey, boolean clearApiKey) {
        if (newApiKey != null && !newApiKey.isBlank()) {
            return newApiKey.trim();
        }
        try {
            return aiIntegrationService.findRetainedKeyOwner(integration, clearApiKey)
                    .map(aiIntegrationService::decryptApiKey)
                    .orElse(null);
        } catch (AiIntegrationService.ProviderChangedException e) {
            throw new ConfigurationProblem("aiTest.error.providerChangedNeedsKey");
        }
    }

    private Step probeConnection(AiClient client) {
        try {
            ChatTurn turn = client.chatWithTools(List.of(), CONNECTION_PROMPT, List.of(),
                    SYSTEM_PROMPT, null, null);
            String reply = abbreviate(turn.assistantText());
            if (reply.isBlank()) {
                // The provider answered, so the connection works, but the model
                // produced no text - typically a reasoning model whose budget ran out.
                return Step.of(CONNECTION, Status.WARNING, turn.stopReason() == StopReason.MAX_TOKENS
                        ? "aiTest.connection.emptyMaxTokens" : "aiTest.connection.empty");
            }
            return Step.of(CONNECTION, Status.OK, "aiTest.connection.ok", reply);
        } catch (RuntimeException e) {
            log.info("AI connection test failed: {}", e.toString());
            return describeFailure(CONNECTION, e);
        }
    }

    private Step probeToolCalling(AiClient client, AiIntegration integration, Step connection) {
        if (connection.status() == Status.FAILED) {
            return skippedToolStep();
        }
        if (!client.supportsNativeTools()) {
            return Step.of(TOOL_CALLING, Status.SKIPPED, integration.isUseLegacyToolCalling()
                    ? "aiTest.tools.disabled" : "aiTest.tools.unsupportedProvider");
        }
        try {
            ChatTurn turn = client.chatWithTools(List.of(), TOOL_PROMPT, List.of(probeTool()),
                    SYSTEM_PROMPT, null, null);
            boolean called = turn.toolCalls().stream()
                    .anyMatch(call -> PROBE_TOOL_NAME.equals(call.name()));
            if (called) {
                return Step.of(TOOL_CALLING, Status.OK, "aiTest.tools.ok");
            }
            if (turn.hasToolCalls()) {
                return Step.of(TOOL_CALLING, Status.WARNING, "aiTest.tools.wrongTool",
                        turn.toolCalls().getFirst().name());
            }
            return Step.of(TOOL_CALLING, Status.WARNING, "aiTest.tools.notCalled");
        } catch (RuntimeException e) {
            log.info("AI tool-calling test failed: {}", e.toString());
            // The plain prompt already went through, so an HTTP 4xx here means
            // the server or model rejected the tools parameter.
            RestClientResponseException http = Throwables.findCause(e, RestClientResponseException.class);
            if (http != null && http.getStatusCode().is4xxClientError()) {
                return Step.of(TOOL_CALLING, Status.FAILED, "aiTest.tools.rejected",
                        http.getStatusCode().value()).withDetail(providerMessage(http));
            }
            return describeFailure(TOOL_CALLING, e);
        }
    }

    static ToolDescriptor probeTool() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode status = schema.putObject("properties").putObject("status");
        status.put("type", "string");
        status.put("description", "Always \"ok\".");
        status.putArray("enum").add("ok");
        schema.putArray("required").add("status");
        return new ToolDescriptor(PROBE_TOOL_NAME, "Confirms that tool calling works. Call it with status \"ok\".", schema);
    }

    /** Maps a transport or HTTP failure to the most likely cause an operator can act on. */
    static Step describeFailure(String name, Throwable error) {
        RestClientResponseException http = Throwables.findCause(error, RestClientResponseException.class);
        if (http != null) {
            int status = http.getStatusCode().value();
            String key = switch (status) {
                case 401, 403 -> "aiTest.error.unauthorized";
                case 404 -> "aiTest.error.notFound";
                case 407 -> "aiTest.error.proxyAuth";
                case 429 -> "aiTest.error.rateLimited";
                default -> status >= 500 ? "aiTest.error.serverError" : "aiTest.error.http";
            };
            return Step.of(name, Status.FAILED, key, status).withDetail(providerMessage(http));
        }
        UnknownHostException unknownHost = Throwables.findCause(error, UnknownHostException.class);
        if (unknownHost != null) {
            return Step.of(name, Status.FAILED, "aiTest.error.unknownHost", Objects.toString(unknownHost.getMessage(), ""));
        }
        Throwable tls = Throwables.findCause(error, cause -> cause instanceof SSLException || cause instanceof CertificateException);
        if (tls != null) {
            return Step.of(name, Status.FAILED, "aiTest.error.tls", safeMessage(tls));
        }
        if (Throwables.hasCause(error, AiConnectionTestService::isConnectTimeout)) {
            return Step.of(name, Status.FAILED, "aiTest.error.connectTimeout");
        }
        if (Throwables.hasCause(error, cause -> cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException)) {
            // Connected, but no answer in time: a model still loading, slow hardware or long reasoning.
            return Step.of(name, Status.FAILED, "aiTest.error.readTimeout");
        }
        if (Throwables.hasCause(error, cause -> cause instanceof ConnectException || cause instanceof NoRouteToHostException)) {
            return Step.of(name, Status.FAILED, "aiTest.error.connectionRefused");
        }
        return Step.of(name, Status.FAILED, "aiTest.error.unexpected", safeMessage(error));
    }

    /**
     * A connect timeout points at the network; a read timeout at the model.
     * Covers Apache HttpClient 5, the JDK client and plain sockets ("Connect timed out").
     */
    private static boolean isConnectTimeout(Throwable cause) {
        return cause instanceof ConnectTimeoutException || cause instanceof HttpConnectTimeoutException
                || cause instanceof SocketTimeoutException && cause.getMessage() != null
                        && cause.getMessage().toLowerCase(Locale.ROOT).contains("connect timed out");
    }

    /**
     * The provider's explanation from an error body: the message field of the
     * usual JSON shapes (OpenAI, Anthropic, Google, Ollama, FastAPI-style
     * servers), otherwise the body itself. Null for an empty body or an HTML
     * page, which a proxy or gateway usually sent.
     */
    static String providerMessage(RestClientResponseException http) {
        String body = http.getResponseBodyAsString().strip();
        if (body.isEmpty() || body.startsWith("<")) {
            return null;
        }
        String message = body;
        if (body.startsWith("{")) {
            try {
                JsonNode root = JSON.readTree(body);
                message = firstString(root.path("error").path("message"), root.path("error"),
                        root.path("message"), root.path("detail"));
                if (message == null) {
                    message = body;
                }
            } catch (JacksonException e) {
                // Not JSON after all; show it as text.
            }
        }
        return abbreviate(message, MAX_DETAIL_CHARS);
    }

    private static String firstString(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isString() && !candidate.asString().isBlank()) {
                return candidate.asString();
            }
        }
        return null;
    }

    private static Step skippedToolStep() {
        return Step.of(TOOL_CALLING, Status.SKIPPED, "aiTest.tools.skippedAfterFailure");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return abbreviate(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    private static String abbreviate(String text) {
        return abbreviate(text, MAX_REPLY_CHARS);
    }

    private static String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "…";
    }

    /** A form value that prevents building a client at all. */
    private static final class ConfigurationProblem extends RuntimeException {
        private final String messageKey;

        ConfigurationProblem(String messageKey) {
            super(messageKey, null, false, false);
            this.messageKey = messageKey;
        }
    }
}
