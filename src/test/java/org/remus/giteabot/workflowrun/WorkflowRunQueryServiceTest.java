package org.remus.giteabot.workflowrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.aiusage.AiInteraction;
import org.remus.giteabot.aiusage.AiInteractionRepository;
import org.remus.giteabot.aiusage.AiToolCall;
import org.remus.giteabot.aiusage.AiToolCallRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side of the Workflow runs page against the Flyway schema: filters, the totals computed
 * from interactions and tool calls (only facts are stored), the details of one interaction and the
 * export. Runs with {@code ddl-auto=validate} as production does.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-run-query;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never",
        "ai-usage.raw-payloads-enabled=true"
})
@ActiveProfiles("test")
class WorkflowRunQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired private WorkflowRunQueryService service;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private AiInteractionRepository interactionRepository;
    @Autowired private AiToolCallRepository toolCallRepository;

    private WorkflowRun review;
    private WorkflowRun writer;
    private WorkflowRun running;

    @BeforeEach
    void seed() {
        toolCallRepository.deleteAllInBatch();
        interactionRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();

        // A review on PR #42: round 1 retried after an outage, round 2 truncated, round limit reached.
        review = run("agentic-review", 1L, "Default Bot", "owner", "repo", TriggerKind.PULL_REQUEST, 42L,
                RunStatus.SUCCEEDED, T0, true);
        AiInteraction failedAttempt = interaction(review, 1, 1, RunStatus.ERROR, T0, 500L, null, 0, 0, 0, 0);
        failedAttempt.setErrorMessage("529 overloaded");
        failedAttempt.setStackTrace("trace");
        interactionRepository.save(failedAttempt);
        AiInteraction withTools = interactionRepository.save(interaction(review, 1, 2, RunStatus.SUCCEEDED,
                T0.plusSeconds(1), 2_000L, "TOOL_USE", 100, 10, 50, 0));
        toolCall(withTools, 1, "github", true, 300, null);
        toolCall(withTools, 2, "built-in", false, 100, "no such file");
        interactionRepository.save(interaction(review, 2, 1, RunStatus.SUCCEEDED, T0.plusSeconds(4), 3_000L,
                "MAX_TOKENS", 200, 20, 0, 150));

        // A writer run on issue #7 of another repository, failed.
        writer = run("issue-writer", 2L, "Writer Bot", "group/sub", "app", TriggerKind.ISSUE, 7L,
                RunStatus.ERROR, T0.plusSeconds(3_600), false);
        interactionRepository.save(interaction(writer, 1, 1, RunStatus.SUCCEEDED, T0.plusSeconds(3_600), 1_000L,
                "END_TURN", 10, 5, 0, 0));

        // A run still in progress.
        running = run("agentic-review", 1L, "Default Bot", "owner", "repo", TriggerKind.PULL_REQUEST, 43L,
                RunStatus.RUNNING, T0.plusSeconds(7_200), false);
        interactionRepository.save(interaction(running, 1, 1, RunStatus.RUNNING, T0.plusSeconds(7_200), null,
                null, 0, 0, 0, 0));
    }

    @Test
    void totalsAreComputedFromInteractionsAndToolCalls() {
        RunView view = service.findRuns(filter(), 0, 20, false).getContent().stream()
                .filter(r -> r.run().getId().equals(review.getId())).findFirst().orElseThrow();

        assertThat(view.rounds()).isEqualTo(2);
        assertThat(view.inputTokens()).isEqualTo(300);
        assertThat(view.outputTokens()).isEqualTo(30);
        assertThat(view.cacheCreationInputTokens()).isEqualTo(50);
        assertThat(view.cacheReadInputTokens()).isEqualTo(150);
        assertThat(view.cacheHitRate()).isEqualTo(0.5);
        assertThat(view.modelTimeMs()).isEqualTo(5_500);
        assertThat(view.toolCalls()).isEqualTo(2);
        assertThat(view.failedToolCalls()).isEqualTo(1);
        assertThat(view.toolTimeMs()).isEqualTo(400);
        assertThat(view.outputTruncated()).isTrue();
        assertThat(view.hasWarnings()).isTrue();

        List<AttemptView> attempts = view.attempts();
        assertThat(attempts).extracting(a -> a.row().roundNumber()).containsExactly(1, 1, 2);
        assertThat(attempts).extracting(AttemptView::isFirstOfRound).containsExactly(true, false, true);
        assertThat(attempts).extracting(AttemptView::isFailed).containsExactly(true, false, false);
        assertThat(attempts.getLast().isTruncated()).isTrue();
        assertThat(attempts.get(1).toolCalls()).isEqualTo(2);
        assertThat(attempts.get(1).durationMs(Instant.now())).isEqualTo(2_400);
        assertThat(attempts.getLast().roundLimit()).isTrue();
    }

    @Test
    void runsAreSortedByStartAndPaged() {
        Page<RunView> newestFirst = service.findRuns(filter(), 0, 2, false);
        assertThat(newestFirst.getTotalElements()).isEqualTo(3);
        assertThat(newestFirst.getContent()).extracting(r -> r.run().getId())
                .containsExactly(running.getId(), writer.getId());

        assertThat(service.findRuns(filter(), 0, 20, true).getContent()).extracting(r -> r.run().getId())
                .containsExactly(review.getId(), writer.getId(), running.getId());
        RunView inProgress = service.findRuns(filter(), 0, 20, false).getContent().getFirst();
        assertThat(inProgress.attempts().getFirst().isRunning()).isTrue();
    }

    @Test
    void latestRunsAreTheNewestWithTheirTotals() {
        assertThat(service.latestRuns(2)).extracting(r -> r.run().getId())
                .containsExactly(running.getId(), writer.getId());
        assertThat(service.latestRuns(5)).hasSize(3).last()
                .satisfies(r -> assertThat(r.hasWarnings()).isTrue());
    }

    @Test
    void filtersCombine() {
        assertThat(ids(new WorkflowRunFilter(null, "owner/repo", null, null, null, null, false, false, null, null)))
                .containsExactlyInAnyOrder(review.getId(), running.getId());
        assertThat(ids(new WorkflowRunFilter(null, "group/sub/app", 7L, "issue-writer", 2L, RunStatus.ERROR, false, false,
                null, null))).containsExactly(writer.getId());
        assertThat(ids(new WorkflowRunFilter(review.getId(), null, null, null, null, null, false, false, null, null)))
                .containsExactly(review.getId());
        assertThat(ids(new WorkflowRunFilter(null, "owner/repo", 42L, null, 1L, null, false, false, null, null)))
                .containsExactly(review.getId());
        assertThat(ids(new WorkflowRunFilter(null, null, null, null, null, null, false, false,
                T0.plusSeconds(60), T0.plusSeconds(7_200)))).containsExactly(writer.getId());
        assertThat(ids(new WorkflowRunFilter(null, "no-slash", null, null, null, null, false, false, null, null))).isEmpty();
    }

    @Test
    void onlyWarningsFindsRoundLimitAndTruncatedRuns() {
        WorkflowRun truncatedOnly = run("review", 1L, "Default Bot", "owner", "repo", TriggerKind.PULL_REQUEST, 44L,
                RunStatus.SUCCEEDED, T0.plusSeconds(10), false);
        interactionRepository.save(interaction(truncatedOnly, 1, 1, RunStatus.SUCCEEDED, T0.plusSeconds(10), 100L,
                "MAX_TOKENS", 1, 1, 0, 0));
        // review reached the round limit (and is truncated); truncatedOnly is truncated; the others have none
        assertThat(ids(new WorkflowRunFilter(null, null, null, null, null, null, true, false, null, null)))
                .containsExactlyInAnyOrder(review.getId(), truncatedOnly.getId());
    }

    @Test
    void onlyFailedCallsMatchesRunsWhoseLastAttemptOfARoundFailed() {
        // review: round 1 failed once, the retry succeeded -> no failed call.
        // writer: state ERROR, but its only call succeeded -> no failed call.
        // gaveUp: the second attempt of round 2 failed too, yet the workflow carried on and succeeded.
        WorkflowRun gaveUp = run("review", 1L, "Default Bot", "owner", "repo", TriggerKind.PULL_REQUEST, 45L,
                RunStatus.SUCCEEDED, T0.plusSeconds(20), false);
        interactionRepository.save(interaction(gaveUp, 1, 1, RunStatus.SUCCEEDED, T0.plusSeconds(20), 100L,
                "END_TURN", 1, 1, 0, 0));
        interactionRepository.save(interaction(gaveUp, 2, 1, RunStatus.ERROR, T0.plusSeconds(21), 100L,
                null, 0, 0, 0, 0));
        interactionRepository.save(interaction(gaveUp, 2, 2, RunStatus.ERROR, T0.plusSeconds(22), 100L,
                null, 0, 0, 0, 0));

        WorkflowRunFilter failedCalls = new WorkflowRunFilter(null, null, null, null, null, null, false, true,
                null, null);
        assertThat(ids(failedCalls)).containsExactly(gaveUp.getId());
        assertThat(service.countByState(failedCalls))
                .containsEntry(RunStatus.SUCCEEDED, 1L)
                .containsEntry(RunStatus.ERROR, 0L)
                .containsEntry(RunStatus.RUNNING, 0L);
        assertThat(ids(new WorkflowRunFilter(null, "owner/repo", null, null, null, RunStatus.SUCCEEDED, false, true,
                null, null))).containsExactly(gaveUp.getId());
    }

    @Test
    void stateCountsIgnoreTheSelectedState() {
        assertThat(service.countByState(new WorkflowRunFilter(null, "owner/repo", null, null, null,
                RunStatus.SUCCEEDED, false, false, null, null)))
                .containsEntry(RunStatus.SUCCEEDED, 1L)
                .containsEntry(RunStatus.RUNNING, 1L)
                .containsEntry(RunStatus.ERROR, 0L);
    }

    /**
     * Azure DevOps puts the project into the repository name (org / project/repo), while a legacy
     * run had to guess the split from "org/project/repo#n" (org/project / repo). Both are one project.
     */
    @Test
    void aProjectIsItsWholePathWhereverOwnerAndNameSplitIt() {
        WorkflowRun webhook = run("agentic-review", 1L, "Default Bot", "org", "project/repo", TriggerKind.PULL_REQUEST,
                5L, RunStatus.SUCCEEDED, T0.plusSeconds(10_000), false);
        WorkflowRun legacy = run("legacy", null, null, "org/project", "repo", null, 5L, RunStatus.SUCCEEDED,
                T0.plusSeconds(9_000), false);

        assertThat(service.filterOptions().projects()).containsOnlyOnce("org/project/repo");
        assertThat(ids(new WorkflowRunFilter(null, "org/project/repo", null, null, null, null, false, false,
                null, null))).containsExactlyInAnyOrder(webhook.getId(), legacy.getId());
    }

    @Test
    void filterOptionsListOnlyValuesThatHaveRuns() {
        FilterOptions options = service.filterOptions();

        assertThat(options.projects()).containsExactly("group/sub/app", "owner/repo");
        assertThat(options.workflowTypes()).containsExactly("agentic-review", "issue-writer");
        assertThat(options.bots()).containsExactly(new BotOption(1L, "Default Bot"),
                new BotOption(2L, "Writer Bot"));
    }

    @Test
    void interactionDetailsCarryRunToolCallsAndStackTrace() {
        Long withToolsId = interactionRepository.findAll().stream()
                .filter(i -> i.getRoundNumber() == 1 && i.getAttempt() == 2).findFirst().orElseThrow().getId();
        InteractionDetails details = service.interactionDetails(withToolsId).orElseThrow();

        assertThat(details.row().roundNumber()).isEqualTo(1);
        assertThat(details.roundsInRun()).isEqualTo(2);
        assertThat(details.row().attempt()).isEqualTo(2);
        assertThat(details.run().getId()).isEqualTo(review.getId());
        assertThat(details.toolTimeMs()).isEqualTo(400);
        assertThat(details.failedToolCalls()).isEqualTo(1);
        assertThat(details.toolCalls()).extracting(org.remus.giteabot.aiusage.AiToolCall::getToolName)
                .containsExactly("tool-1", "tool-2");

        Long failedId = interactionRepository.findAll().stream()
                .filter(i -> i.getStatus() == RunStatus.ERROR).findFirst().orElseThrow().getId();
        assertThat(service.interactionDetails(failedId).orElseThrow().stackTrace()).isEqualTo("trace");
        assertThat(service.interactionDetails(-1L)).isEmpty();
    }

    @Test
    void exportContainsRunsWithInteractionsAndToolCalls() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(new WorkflowRunFilter(review.getId(), null, null, null, null, null, false, false, null, null), out);

        JsonNode runs = new ObjectMapper().readTree(out.toByteArray());
        assertThat(runs).hasSize(1);
        JsonNode run = runs.get(0);
        assertThat(run.get("workflowType").asText()).isEqualTo("agentic-review");
        assertThat(run.get("triggerKind").asText()).isEqualTo("PULL_REQUEST");
        assertThat(run.get("interactions")).hasSize(3);
        assertThat(run.get("interactions").get(1).get("toolCalls")).hasSize(2);
        assertThat(run.get("interactions").get(0).get("errorMessage").asText()).isEqualTo("529 overloaded");
        assertThat(run.get("startedAt").asText()).isEqualTo(review.getStartedAt().toString());
        assertThat(run.get("interactions").get(0).get("roundNumber").asInt()).isEqualTo(1);
        assertThat(run.get("interactions").get(1).get("toolCalls").get(0).has("interaction")).isFalse();
    }

    /**
     * Batches continue after the last run written, not at an offset: a run started while the
     * export is under way does not push an already written run into the next batch again.
     */
    @Test
    void exportReadsEveryRunOnceWhileRunsAreAdded() throws Exception {
        WorkflowRun tie = run("issue-writer", 2L, "Writer Bot", "group/sub", "app", TriggerKind.ISSUE, 8L,
                RunStatus.SUCCEEDED, writer.getStartedAt(), false);
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            private boolean added;

            @Override
            public void flush() {
                if (!added && size() > 0) {
                    added = true;
                    run("agentic-review", 1L, "Default Bot", "owner", "repo", TriggerKind.PULL_REQUEST, 44L,
                            RunStatus.RUNNING, T0.plusSeconds(9_000), false);
                }
            }
        };
        service.export(filter(), out, 1);

        List<Long> exported = new ArrayList<>();
        new ObjectMapper().readTree(out.toByteArray()).forEach(run -> exported.add(run.get("id").asLong()));
        assertThat(exported).containsExactly(running.getId(), tie.getId(), writer.getId(), review.getId());
    }

    private List<Long> ids(WorkflowRunFilter filter) {
        return service.findRuns(filter, 0, 20, false).getContent().stream().map(r -> r.run().getId()).toList();
    }

    private static WorkflowRunFilter filter() {
        return new WorkflowRunFilter(null, null, null, null, null, null, false, false, null, null);
    }

    private WorkflowRun run(String type, Long botId, String botName, String owner, String repo, TriggerKind kind,
                            Long number, RunStatus state, Instant startedAt, boolean roundLimit) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowType(type);
        run.setBotId(botId);
        run.setBotName(botName);
        run.setAiIntegrationName("claude");
        run.setGitIntegrationName("gitea");
        run.setRepoOwner(owner);
        run.setRepoName(repo);
        run.setTriggerKind(kind);
        run.setTriggerNumber(number);
        run.setState(state);
        run.setStartedAt(startedAt);
        run.setEndedAt(state == RunStatus.RUNNING ? null : startedAt.plusSeconds(30));
        run.setRoundLimitReached(roundLimit);
        return runRepository.save(run);
    }

    private static AiInteraction interaction(WorkflowRun run, int round, int attempt, RunStatus status,
                                             Instant startedAt, Long durationMs, String stopReason,
                                             long input, long output, long cacheWrite, long cacheRead) {
        AiInteraction interaction = new AiInteraction();
        interaction.setWorkflowRun(run);
        interaction.setRoundNumber(round);
        interaction.setAttempt(attempt);
        interaction.setAiIntegrationName("claude");
        interaction.setStatus(status);
        interaction.setStartedAt(startedAt);
        interaction.setDurationMs(durationMs);
        interaction.setStopReason(stopReason);
        interaction.setInputTokens(input);
        interaction.setOutputTokens(output);
        interaction.setCacheCreationInputTokens(cacheWrite);
        interaction.setCacheReadInputTokens(cacheRead);
        return interaction;
    }

    private void toolCall(AiInteraction interaction, int sequence, String source, boolean success, long durationMs,
                          String error) {
        AiToolCall call = new AiToolCall();
        call.setInteraction(interaction);
        call.setSequence(sequence);
        call.setSource(source);
        call.setToolName("tool-" + sequence);
        call.setSuccess(success);
        call.setDurationMs(durationMs);
        call.setErrorMessage(error);
        call.setArguments("{\"n\":" + sequence + "}");
        toolCallRepository.save(call);
    }
}
