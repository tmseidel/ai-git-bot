package org.remus.giteabot.aiusage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.AuditRun;
import org.remus.giteabot.ai.AuditTrigger;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCallAudit;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.workflowrun.RunStatus;
import org.remus.giteabot.workflowrun.WorkflowRun;
import org.remus.giteabot.workflowrun.WorkflowRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recording and the Usage page queries against the Flyway schema: a workflow run with retried
 * and failed attempts and tool calls is stored with its totals, the row query leaves the raw
 * payloads out, and a full payload ({@code max-raw-payload-length=0}) far beyond 65535 characters
 * is stored and read back by id. Runs with {@code ddl-auto=validate} as production does, so the
 * entity mapping must match the migrations.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-usage-persistence;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never",
        "ai-usage.raw-payloads-enabled=true", "ai-usage.max-raw-payload-length=0",
        "ai-retry.initial-delay=0s", "ai-retry.jitter=0"
})
@ActiveProfiles("test")
class AiUsagePersistenceTest {

    private static final AuditTrigger TRIGGER = new AuditTrigger(3L, "bot", "claude", "gitea",
            "owner", "repo", TriggerKind.PULL_REQUEST, 42L);

    @Autowired private AiUsageService service;
    @Autowired private AiInteractionRepository interactionRepository;
    @Autowired private AiToolCallRepository toolCallRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private AiClientFactory clientFactory;

    @BeforeEach
    void clean() {
        toolCallRepository.deleteAllInBatch();
        interactionRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();
    }

    @AfterEach
    void clearContext() {
        AiAuditContext.clear();
    }

    @Test
    void workflowRunIsStoredWithRoundsAttemptsToolCallsAndTotals() {
        Long runId;
        try (AuditRun run = AiAuditContext.openRun("agentic-review", TRIGGER)) {
            // Round 1: the provider is overloaded once, then answers with a tool call.
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptFailed(new RuntimeException("529 overloaded"));
            service.attemptStarted("claude");
            service.usageReported(100, 10, 50, 0, "{\"r\":1}", "{\"s\":1}");
            service.attemptSucceeded(StopReason.TOOL_USE);
            ToolCallAudit.record("github", "get_file_contents", "{\"path\":\"a\"}",
                    () -> "content", result -> null);
            ToolCallAudit.record(ToolCallAudit.BUILT_IN, "grep", "[]",
                    () -> "ERROR: no such file", ToolCallAudit::errorOfTextResult);

            // Round 2: the model runs out of output tokens.
            run.beginCall();
            service.attemptStarted("claude");
            service.usageReported(200, 20, 0, 50, null, null);
            service.attemptSucceeded(StopReason.MAX_TOKENS);
            run.markRoundLimitReached();
            runId = run.getRunId();
        }

        WorkflowRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getWorkflowType()).isEqualTo("agentic-review");
        assertThat(stored.getBotName()).isEqualTo("bot");
        assertThat(stored.getRepoOwner()).isEqualTo("owner");
        assertThat(stored.getTriggerNumber()).isEqualTo(42L);
        assertThat(stored.getState()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stored.getEndedAt()).isNotNull();
        assertThat(stored.isRoundLimitReached()).isTrue();

        List<AiInteraction> interactions = interactionsOf(runId);
        assertThat(interactions).extracting(AiInteraction::getRoundNumber).containsExactly(1, 1, 2);
        assertThat(interactions).extracting(AiInteraction::getAttempt).containsExactly(1, 2, 1);
        assertThat(interactions).extracting(AiInteraction::getStatus).containsExactly(
                RunStatus.ERROR, RunStatus.SUCCEEDED, RunStatus.SUCCEEDED);
        assertThat(interactions).extracting(AiInteraction::getStopReason).containsExactly(null, "TOOL_USE", "MAX_TOKENS");
        assertThat(interactions.getFirst().getErrorMessage()).isEqualTo("529 overloaded");
        assertThat(interactions).allSatisfy(i -> assertThat(i.getDurationMs()).isNotNull());
        assertThat(interactions).extracting(AiInteraction::getInputTokens).containsExactly(0L, 100L, 200L);
        assertThat(interactions).extracting(AiInteraction::getCacheCreationInputTokens).containsExactly(0L, 50L, 0L);
        assertThat(interactions).extracting(AiInteraction::getCacheReadInputTokens).containsExactly(0L, 0L, 50L);

        AiInteraction withTools = interactions.get(1);
        List<AiToolCall> calls = toolCallsOf(withTools.getId());
        assertThat(calls).extracting(AiToolCall::getSequence).containsExactly(1, 2);
        assertThat(calls).extracting(AiToolCall::getSource).containsExactly("github", ToolCallAudit.BUILT_IN);
        assertThat(calls.get(0).getArguments()).isEqualTo("{\"path\":\"a\"}");
        assertThat(calls).extracting(AiToolCall::isSuccess).containsExactly(true, false);
        assertThat(calls.get(1).getErrorMessage()).isEqualTo("no such file");
    }

    /**
     * The real decorator chain the factory builds, around a fake provider: a provider outage the
     * retry wrapper recovers from is attempt 2 of the same round, and a failure that is not
     * retried is stored exactly once, all tagged with the integration name.
     */
    @Test
    void factoryBuiltClientRecordsRetriesAndFailuresOncePerAttempt() {
        FakeProvider provider = new FakeProvider();
        AiClient client = clientFactory.decorate(provider, "claude");
        Long runId;
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            assertThat(client.submitReviewPrompt("sys", null, "diff")).isEqualTo("LGTM");
            assertThatThrownBy(() -> client.chat(List.of(), "hi", "sys", null))
                    .isInstanceOf(HttpClientErrorException.class);
            runId = run.getRunId();
        }

        List<AiInteraction> interactions = interactionsOf(runId);
        assertThat(interactions).extracting(AiInteraction::getRoundNumber).containsExactly(1, 1, 2);
        assertThat(interactions).extracting(AiInteraction::getAttempt).containsExactly(1, 2, 1);
        assertThat(interactions).extracting(AiInteraction::getStatus).containsExactly(
                RunStatus.ERROR, RunStatus.SUCCEEDED, RunStatus.ERROR);
        assertThat(interactions).extracting(AiInteraction::getAiIntegrationName).containsOnly("claude");
        assertThat(interactions.get(1).getInputTokens()).isEqualTo(10);
        assertThat(interactions.get(2).getErrorMessage()).contains("401");

        assertThat(runRepository.findById(runId)).isPresent();
    }

    /** Fails the first review request with an outage, then answers; every chat is rejected. */
    private static final class FakeProvider extends AbstractAiClient {
        private int reviewRequests;

        FakeProvider() {
            super("fake-model", 100);
        }

        @Override
        protected String sendReviewRequest(String systemPrompt, String effectiveModel, int maxTokens,
                                           String userMessage) {
            if (++reviewRequests == 1) {
                throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                        HttpHeaders.EMPTY, new byte[0], null);
            }
            reportUsage(10, 5, 0, 0, null, null);
            return "LGTM";
        }

        @Override
        protected String sendChatRequest(String systemPrompt, String effectiveModel, int maxTokens,
                                         List<AiMessage> messages) {
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "401 Unauthorized",
                    HttpHeaders.EMPTY, new byte[0], null);
        }
    }

    @Test
    void errorCountTakesTheLastFailedAttemptOfEachRound() {
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            // Round 1: fails once, the retry succeeds — not counted.
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptFailed(new RuntimeException("529 overloaded"));
            service.attemptStarted("claude");
            service.attemptSucceeded(StopReason.END_TURN);
            // Round 2: fails twice — counted once.
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptFailed(new RuntimeException("529 overloaded"));
            service.attemptStarted("claude");
            service.attemptFailed(new RuntimeException("529 overloaded"));
            // Round 3: an announced retry recovers from a failure — not counted.
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptFailed(new RuntimeException("Read timed out"));
            run.markRetry();
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptSucceeded(StopReason.END_TURN);
            // Round 4: fails once — counted.
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptFailed(new IllegalStateException("401 Unauthorized"));
        }

        assertThat(service.countErrorsSince(java.time.Instant.EPOCH)).isEqualTo(2);
        assertThat(service.countErrorsSince(java.time.Instant.now().plusSeconds(60))).isZero();
    }

    @Test
    void failedWorkflowRunStoresItsFailure() {
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            run.beginCall();
            service.attemptStarted("claude");
            service.attemptSucceeded(StopReason.END_TURN);
            run.fail("Workflow failed: no diff");
        }

        assertThat(runRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getWorkflowType()).isEqualTo("review");
            assertThat(run.getState()).isEqualTo(RunStatus.ERROR);
            assertThat(run.getErrorMessage()).isEqualTo("Workflow failed: no diff");
        });
    }


    @Test
    void clearKeepsRunningRunsAndRecoveryFailsThem() {
        try (AuditRun finished = AiAuditContext.openRun("review", TRIGGER)) {
            finished.beginCall();
            service.attemptStarted("claude");
            service.attemptSucceeded(StopReason.END_TURN);
            ToolCallAudit.record("github", "list", "{}", () -> "ok", result -> null);
        }
        AuditRun running = AiAuditContext.openRun("review", TRIGGER);
        running.beginCall();
        service.attemptStarted("claude");

        service.clearUsage();

        assertThat(runRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getId()).isEqualTo(running.getRunId()));
        assertThat(toolCallRepository.count()).isZero();

        service.failInterruptedRuns();

        WorkflowRun interrupted = runRepository.findById(running.getRunId()).orElseThrow();
        assertThat(interrupted.getState()).isEqualTo(RunStatus.ERROR);
        assertThat(interrupted.getErrorMessage()).isEqualTo(AiUsageService.INTERRUPTED_MESSAGE);
        assertThat(interactionRepository.findAll()).singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(RunStatus.ERROR));
    }

    @Test
    void rowsComeWithoutPayloadsAndThePayloadLoadsInFullById() {
        String big = "{\"messages\":\"" + "x".repeat(150_000) + "\"}";
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            recordUsage(run, "small", 10, 1, "{\"a\":1}", "{\"b\":2}");
            recordUsage(run, "big", 30, 3, big, null);
            run.beginCall();
            service.attemptStarted("broken");
            service.attemptFailed(new IllegalStateException("401 Unauthorized"));
            run.fail("401 Unauthorized");
        }

        Page<AiUsageRow> rows = service.findUsage(null, null, 0, 20, "inputTokens", false);

        assertThat(rows.getTotalElements()).isEqualTo(2);
        assertThat(rows.getContent()).extracting(AiUsageRow::aiIntegrationName).containsExactly("big", "small");
        AiUsageRow bigRow = rows.getContent().getFirst();
        assertThat(bigRow.round()).isEqualTo(2);

        AiUsageRawPayload payload = service.findRawPayload(bigRow.id()).orElseThrow();
        assertThat(payload.rawRequest()).isEqualTo(big);
        assertThat(payload.rawResponse()).isNull();
        assertThat(service.findRawPayload(-1L)).isEmpty();

        Page<AiErrorRow> errors = service.findErrors(null, null, 0, 20, "timestamp", false);
        assertThat(errors.getContent()).singleElement().satisfies(error -> {
            assertThat(error.aiIntegrationName()).isEqualTo("broken");
            assertThat(error.errorMessage()).isEqualTo("401 Unauthorized");
        });
        assertThat(service.countErrorsSince(java.time.Instant.EPOCH)).isEqualTo(1);
        assertThat(service.totalInputTokens()).isEqualTo(40);
    }

    private void recordUsage(AuditRun run, String integration, long input, long output, String rawRequest,
                             String rawResponse) {
        run.beginCall();
        service.attemptStarted(integration);
        service.usageReported(input, output, 0, 0, rawRequest, rawResponse);
        service.attemptSucceeded(StopReason.END_TURN);
    }

    private List<AiInteraction> interactionsOf(Long runId) {
        return interactionRepository.findAll().stream()
                .filter(interaction -> runId.equals(interaction.getWorkflowRun().getId()))
                .sorted(java.util.Comparator.comparingInt(AiInteraction::getRoundNumber)
                        .thenComparingInt(AiInteraction::getAttempt))
                .toList();
    }

    private List<AiToolCall> toolCallsOf(Long interactionId) {
        return toolCallRepository.findAll().stream()
                .filter(call -> interactionId.equals(call.getInteraction().getId()))
                .sorted(java.util.Comparator.comparingInt(AiToolCall::getSequence))
                .toList();
    }
}
