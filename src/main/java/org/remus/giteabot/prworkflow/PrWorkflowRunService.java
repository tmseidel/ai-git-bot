package org.remus.giteabot.prworkflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrWorkflowRunService {

    static final int MAX_LOG_EXCERPT_CHARS = 8 * 1024;
    static final int MAX_SUMMARY_CHARS = 2000;
    static final int CALLBACK_SECRET_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PrWorkflowRunRepository runRepository;
    private final PrWorkflowStepRepository stepRepository;

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
        run.setCallbackSecret(generateCallbackSecret());
        return runRepository.save(run);
    }

    @Transactional
    public void cancelActiveRunsForPr(Long botId, String repoOwner, String repoName, Long prNumber,
                                      String workflowKey) {
        List<PrWorkflowRun> active = runRepository
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                        botId, repoOwner, repoName, prNumber, workflowKey,
                        List.of(PrWorkflowRunStatus.RUNNING,
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

    @Transactional(readOnly = true)
    public boolean isActive(Long runId) {
        if (runId == null) {
            return false;
        }
        return runRepository.findById(runId)
                .map(PrWorkflowRun::getStatus)
                .map(PrWorkflowRunStatus::isActive)
                .orElse(false);
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

    @Transactional
    public PrWorkflowRun markWaitingDeploy(Long runId, String deploymentHandleJson) {
        PrWorkflowRun run = runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            log.debug("Ignoring markWaitingDeploy() for already-terminal run id={} status={}",
                    runId, run.getStatus());
            return run;
        }
        run.setStatus(PrWorkflowRunStatus.WAITING_DEPLOY);
        run.setDeploymentHandleJson(deploymentHandleJson);
        return runRepository.save(run);
    }

    @Transactional
    public PrWorkflowRun resumeFromDeploy(Long runId, String previewUrl) {
        PrWorkflowRun run = runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        if (run.getStatus() != PrWorkflowRunStatus.WAITING_DEPLOY) {
            log.debug("Ignoring resumeFromDeploy() for run id={} (not WAITING_DEPLOY, was {})",
                    runId, run.getStatus());
            return run;
        }
        run.setStatus(PrWorkflowRunStatus.RUNNING);
        if (previewUrl != null && !previewUrl.isBlank()) {
            run.setPreviewUrl(truncate(previewUrl, 2048));
        }
        return runRepository.save(run);
    }

    @Transactional
    public PrWorkflowRun setPreviewUrl(Long runId, String previewUrl) {
        PrWorkflowRun run = runRepository.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + runId));
        if (previewUrl == null || previewUrl.isBlank()) {
            return run;
        }
        run.setPreviewUrl(truncate(previewUrl, 2048));
        return runRepository.save(run);
    }

    private static String generateCallbackSecret() {
        byte[] bytes = new byte[CALLBACK_SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
