package org.remus.giteabot.prworkflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Lifecycle service for {@link PrWorkflowRun} rows. The
 * {@link PrWorkflowOrchestrator} is the only intended caller; tests may also
 * use it directly. All write methods are transactional and idempotent for the
 * given run id.
 *
 * <p>Limits the maximum length of {@link PrWorkflowStep#getLogExcerpt()} so
 * one misbehaving workflow cannot fill the database with multi-megabyte
 * blobs. The limit is intentionally small (8&nbsp;KB) — long-form logs belong
 * in the application log, not in the per-run audit trail.</p>
 */
@Slf4j
@Service
public class PrWorkflowRunService {

    static final int MAX_LOG_EXCERPT_CHARS = 8 * 1024;
    static final int MAX_SUMMARY_CHARS = 2000;

    private final PrWorkflowRunRepository runRepository;
    private final PrWorkflowStepRepository stepRepository;

    public PrWorkflowRunService(PrWorkflowRunRepository runRepository,
                                PrWorkflowStepRepository stepRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional
    public PrWorkflowRun start(Long botId, String repoOwner, String repoName, Long prNumber,
                               String workflowKey) {
        cancelActiveRunsForPr(botId, repoOwner, repoName, prNumber, workflowKey);
        PrWorkflowRun run = new PrWorkflowRun();
        run.setBotId(botId);
        run.setRepoOwner(repoOwner);
        run.setRepoName(repoName);
        run.setPrNumber(prNumber);
        run.setWorkflowKey(workflowKey);
        run.setStatus(PrWorkflowRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    /**
     * Transitions every still-active run for the same (bot, repo, pr, workflow)
     * tuple to {@link PrWorkflowRunStatus#CANCELLED}. Called from
     * {@link #start} so a new PR-synchronize event automatically supersedes
     * any earlier in-flight run.
     */
    @Transactional
    public void cancelActiveRunsForPr(Long botId, String repoOwner, String repoName, Long prNumber,
                                      String workflowKey) {
        List<PrWorkflowRun> active = runRepository
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                        botId, repoOwner, repoName, prNumber, workflowKey,
                        List.of(PrWorkflowRunStatus.QUEUED,
                                PrWorkflowRunStatus.RUNNING,
                                PrWorkflowRunStatus.WAITING_DEPLOY));
        if (active.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (PrWorkflowRun run : active) {
            run.setStatus(PrWorkflowRunStatus.CANCELLED);
            run.setFinishedAt(now);
            String existing = run.getSummary() == null ? "" : run.getSummary();
            run.setSummary(truncate(("Superseded by a newer run. " + existing).trim(), MAX_SUMMARY_CHARS));
        }
        runRepository.saveAll(active);
        log.debug("Cancelled {} prior run(s) for bot={} repo={}/{} pr=#{} workflow={}",
                active.size(), botId, repoOwner, repoName, prNumber, workflowKey);
    }

    @Transactional
    public PrWorkflowRun appendStep(Long runId, String name, String status, String logExcerpt) {
        PrWorkflowRun run = runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        PrWorkflowStep step = new PrWorkflowStep();
        step.setRun(run);
        step.setStepOrder(run.getSteps().size());
        step.setName(name == null ? "step" : name);
        step.setStatus(status == null ? "INFO" : status);
        step.setLogExcerpt(truncate(logExcerpt, MAX_LOG_EXCERPT_CHARS));
        run.getSteps().add(step);
        stepRepository.save(step);
        return run;
    }

    @Transactional
    public PrWorkflowRun complete(Long runId, PrWorkflowRunStatus status, String summary) {
        PrWorkflowRun run = runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            log.debug("Ignoring complete() for already-terminal run id={} status={}",
                    runId, run.getStatus());
            return run;
        }
        run.setStatus(status);
        run.setSummary(truncate(summary, MAX_SUMMARY_CHARS));
        run.setFinishedAt(Instant.now());
        return runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public PrWorkflowRun getById(Long runId) {
        return runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}

