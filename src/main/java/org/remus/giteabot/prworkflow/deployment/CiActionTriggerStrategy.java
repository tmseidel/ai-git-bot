package org.remus.giteabot.prworkflow.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.WorkflowDispatchRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M6 — Deployment strategy that triggers the Git provider's <em>native</em>
 * CI (GitHub Actions, Gitea Actions ≥ 1.21, GitLab CI, Bitbucket Pipelines)
 * via the {@link RepositoryApiClient} extensions instead of an outbound
 * webhook.
 *
 * <p>Strategy configuration JSON:</p>
 * <pre>
 * {
 *   "workflowRef":        "preview.yml",                  // workflow file (GH/Gitea) | trigger token (GitLab) | pipeline pattern (Bitbucket)
 *   "refTemplate":        "refs/heads/{branch}",          // optional, default = "refs/heads/{branch}"; MUST resolve to a branch or tag on the remote — Gitea/GitLab/Bitbucket reject "refs/pull/N/head"; only GitHub Actions also accepts that form (for fork PRs)
 *   "previewUrlOutput":   "preview_url",                  // optional, key into getWorkflowRunOutputs(...) (GitLab only)
 *   "pollIntervalSeconds": 15,                            // optional, default 15 (clamped 5..120)
 *   "inputs": {                                           // optional, extra inputs/variables for the CI dispatch
 *     "preview_branch": "pr-{prNumber}",
 *     "callbackUrl":   "{callbackUrl}",
 *     "callbackSecret":"{callbackSecret}"
 *   }
 * }
 * </pre>
 *
 * <p>The strategy is <em>asynchronous</em>: {@link #trigger(DeploymentRequest)}
 * dispatches and immediately returns {@link DeploymentResult#pending(String)}.
 * The {@link CiActionPoller} drives the run forward by calling
 * {@link RepositoryApiClient#getWorkflowRun(String, String, String)} on a
 * scheduled cadence and publishing a callback via
 * {@link DeploymentCallbackNotifier} when the workflow reaches a terminal
 * state. We therefore declare {@link #awaitsCallback()} {@code = true} so
 * the {@link DeploymentOrchestrator} blocks on the existing notifier
 * mechanism — see {@code doc/PR_WORKFLOWS_CI_ACTIONS.md}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CiActionTriggerStrategy implements DeploymentStrategy {

    public static final String CONFIG_WORKFLOW_REF = "workflowRef";
    public static final String CONFIG_REF_TEMPLATE = "refTemplate";
    public static final String CONFIG_PREVIEW_URL_OUTPUT = "previewUrlOutput";
    public static final String CONFIG_POLL_INTERVAL_SECONDS = "pollIntervalSeconds";
    public static final String CONFIG_INPUTS = "inputs";

    public static final String HANDLE_STRATEGY = "strategy";
    public static final String HANDLE_PROVIDER = "provider";
    public static final String HANDLE_RUN_ID = "runId";
    public static final String HANDLE_OWNER = "owner";
    public static final String HANDLE_REPO = "repo";
    public static final String HANDLE_WORKFLOW_REF = "workflowRef";
    public static final String HANDLE_PREVIEW_URL_OUTPUT = "previewUrlOutput";
    public static final String HANDLE_INTEGRATION_ID = "integrationId";
    public static final String HANDLE_POLL_INTERVAL = "pollIntervalSeconds";

    static final String DEFAULT_REF_TEMPLATE = "refs/heads/{branch}";
    static final int DEFAULT_POLL_INTERVAL_SECONDS = 15;
    static final int MIN_POLL_INTERVAL_SECONDS = 5;
    static final int MAX_POLL_INTERVAL_SECONDS = 120;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BotRepository botRepository;
    private final GiteaClientFactory clientFactory;

    @Override
    public DeploymentStrategyType typeKey() {
        return DeploymentStrategyType.CI_ACTION;
    }

    /**
     * Returns {@code true}: the per-run {@link DeploymentOrchestrator} thread
     * blocks on the {@link DeploymentCallbackNotifier} queue and
     * {@link CiActionPoller} pushes the result through it once the polled
     * CI run reports a terminal state.
     */
    @Override
    public boolean awaitsCallback() {
        return true;
    }

    @Override
    public DeploymentResult trigger(DeploymentRequest request) {
        JsonNode config;
        try {
            String raw = request.target().getConfigJson();
            config = (raw == null || raw.isBlank())
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(raw);
        } catch (Exception e) {
            return DeploymentResult.rejected("CI_ACTION config is not valid JSON: " + e.getMessage());
        }

        String workflowRef = textOrNull(config, CONFIG_WORKFLOW_REF);
        if (workflowRef == null || workflowRef.isBlank()) {
            return DeploymentResult.rejected("CI_ACTION config is missing '" + CONFIG_WORKFLOW_REF + "'");
        }

        Bot bot = botRepository.findByIdWithIntegrations(request.run().getBotId()).orElse(null);
        if (bot == null) {
            return DeploymentResult.rejected("Bot id=" + request.run().getBotId() + " no longer exists");
        }
        GitIntegration integration = bot.getGitIntegration();
        if (integration == null) {
            return DeploymentResult.rejected("Bot '" + bot.getName() + "' has no Git integration configured");
        }
        RepositoryApiClient client = clientFactory.getApiClient(integration);

        String refTemplate = textOr(config, CONFIG_REF_TEMPLATE, DEFAULT_REF_TEMPLATE);
        String gitRef = applyPlaceholders(refTemplate, request);
        Map<String, String> inputs = renderInputs(config.get(CONFIG_INPUTS), request);
        int pollIntervalSeconds = clampPollInterval(intOr(config, CONFIG_POLL_INTERVAL_SECONDS,
                DEFAULT_POLL_INTERVAL_SECONDS));
        String previewUrlOutput = textOr(config, CONFIG_PREVIEW_URL_OUTPUT, "preview_url");

        String runId;
        try {
            runId = client.dispatchWorkflow(new WorkflowDispatchRequest(
                    request.repoOwner(), request.repoName(), workflowRef, gitRef, inputs));
        } catch (UnsupportedOperationException e) {
            return DeploymentResult.rejected("Provider '" + integration.getProviderType()
                    + "' does not support CI_ACTION dispatch yet");
        } catch (Exception e) {
            log.warn("CI_ACTION dispatch failed for run id={} pr=#{} workflow={}: {}",
                    request.run().getId(), request.prNumber(), workflowRef, e.getMessage());
            return DeploymentResult.failed("CI dispatch failed: " + e.getMessage(), "{}");
        }

        String handle = buildHandle(integration, request, workflowRef, runId,
                previewUrlOutput, pollIntervalSeconds);
        log.info("CI_ACTION dispatched workflow '{}' (run id={}) for PR #{} on {}/{} (ref={})",
                workflowRef, runId, request.prNumber(), request.repoOwner(),
                request.repoName(), gitRef);
        return DeploymentResult.pending(handle);
    }

    @Override
    public void teardown(PrWorkflowRun run) {
        // CI providers tear down their own runners when the pipeline ends.
        // The poller drops the handle from its in-memory map automatically
        // on terminal status; nothing to do here.
    }

    // ---- public for the poller / tests ----

    /** Parsed view of the handle JSON written by {@link #trigger(DeploymentRequest)}. */
    public record Handle(
            RepositoryType provider,
            Long integrationId,
            String owner,
            String repo,
            String workflowRef,
            String runId,
            String previewUrlOutput,
            int pollIntervalSeconds) {

        public boolean isComplete() {
            return provider != null && integrationId != null && owner != null && repo != null
                    && runId != null && !runId.isBlank();
        }
    }

    public static Optional<Handle> parseHandle(String handleJson) {
        if (handleJson == null || handleJson.isBlank()) return Optional.empty();
        try {
            JsonNode node = OBJECT_MAPPER.readTree(handleJson);
            if (!node.isObject()) return Optional.empty();
            String strategy = textOrNull(node, HANDLE_STRATEGY);
            if (!DeploymentStrategyType.CI_ACTION.key().equalsIgnoreCase(strategy)) {
                return Optional.empty();
            }
            RepositoryType provider = null;
            String providerStr = textOrNull(node, HANDLE_PROVIDER);
            if (providerStr != null) {
                try {
                    provider = RepositoryType.valueOf(providerStr);
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
            JsonNode intIdNode = node.get(HANDLE_INTEGRATION_ID);
            Long integrationId = (intIdNode != null && intIdNode.canConvertToLong()) ? intIdNode.asLong() : null;
            int pollInterval = node.has(HANDLE_POLL_INTERVAL) && node.get(HANDLE_POLL_INTERVAL).canConvertToInt()
                    ? node.get(HANDLE_POLL_INTERVAL).asInt() : DEFAULT_POLL_INTERVAL_SECONDS;
            Handle handle = new Handle(
                    provider,
                    integrationId,
                    textOrNull(node, HANDLE_OWNER),
                    textOrNull(node, HANDLE_REPO),
                    textOrNull(node, HANDLE_WORKFLOW_REF),
                    textOrNull(node, HANDLE_RUN_ID),
                    textOrNull(node, HANDLE_PREVIEW_URL_OUTPUT),
                    clampPollInterval(pollInterval));
            return handle.isComplete() ? Optional.of(handle) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---- internals ----

    private String buildHandle(GitIntegration integration, DeploymentRequest request,
                               String workflowRef, String runId, String previewUrlOutput,
                               int pollIntervalSeconds) {
        ObjectNode handle = OBJECT_MAPPER.createObjectNode();
        handle.put(HANDLE_STRATEGY, DeploymentStrategyType.CI_ACTION.key());
        handle.put(HANDLE_PROVIDER, integration.getProviderType().name());
        handle.put(HANDLE_INTEGRATION_ID, integration.getId());
        handle.put(HANDLE_OWNER, request.repoOwner());
        handle.put(HANDLE_REPO, request.repoName());
        handle.put(HANDLE_WORKFLOW_REF, workflowRef);
        handle.put(HANDLE_RUN_ID, runId);
        handle.put(HANDLE_PREVIEW_URL_OUTPUT, previewUrlOutput);
        handle.put(HANDLE_POLL_INTERVAL, pollIntervalSeconds);
        try {
            return OBJECT_MAPPER.writeValueAsString(handle);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> renderInputs(JsonNode inputsNode, DeploymentRequest request) {
        if (inputsNode == null || !inputsNode.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        inputsNode.properties().forEach(entry -> {
            String value = entry.getValue() == null || entry.getValue().isNull()
                    ? "" : entry.getValue().asText();
            out.put(entry.getKey(), applyPlaceholders(value, request));
        });
        return out;
    }

    static String applyPlaceholders(String template, DeploymentRequest request) {
        if (template == null) return "";
        return template
                .replace("{prNumber}", String.valueOf(request.prNumber()))
                .replace("{sha}", request.sha() == null ? "" : request.sha())
                .replace("{branch}", request.branch() == null ? "" : request.branch())
                .replace("{repoOwner}", request.repoOwner() == null ? "" : request.repoOwner())
                .replace("{repoName}", request.repoName() == null ? "" : request.repoName())
                .replace("{runId}", String.valueOf(request.run().getId()))
                .replace("{callbackUrl}", request.callbackUrl() == null ? "" : request.callbackUrl())
                .replace("{callbackSecret}", request.run().getCallbackSecret() == null
                        ? "" : request.run().getCallbackSecret());
    }

    static int clampPollInterval(int seconds) {
        if (seconds < MIN_POLL_INTERVAL_SECONDS) return MIN_POLL_INTERVAL_SECONDS;
        return Math.min(seconds, MAX_POLL_INTERVAL_SECONDS);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = textOrNull(node, field);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        if (node == null) return fallback;
        JsonNode v = node.get(field);
        return (v == null || v.isNull() || !v.canConvertToInt()) ? fallback : v.asInt();
    }
}

