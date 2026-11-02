package org.remus.giteabot.prworkflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.remus.giteabot.prworkflow.agentreview.ReviewRevision;
import org.remus.giteabot.prworkflow.agentreview.ReviewDraft;

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

    /** Captures the immutable revision once, only while the owning run is active. */
    @Transactional
    public void captureReviewRevision(Long runId, ReviewRevision revision) {
        if (runId == null || revision == null || runRepository.captureReviewRevision(runId,
                revision.headSha(), revision.baseSha(), revision.diffHash()) != 1) {
            throw new IllegalStateException("Review revision could not be captured for an active run");
        }
    }

    /** Atomically writes a validated snapshot, rejecting stale versions, revisions, cancelled or sealed runs. */
    @Transactional
    public void saveReviewDraft(Long runId, ReviewRevision revision, long expected, ReviewDraft draft) {
        if (runId == null || revision == null || draft == null || draft.status() != ReviewDraft.Status.DRAFT
                || expected < 0 || draft.version() != expected + 1
                || runRepository.saveReviewDraft(runId, expected, draft.version(), draft.json(), draft.status().name(),
                revision.headSha(), revision.baseSha(), revision.diffHash()) != 1) {
            throw new IllegalStateException("Review draft write lost its active run, version or revision");
        }
    }

    /** Reloads the supported snapshot and verifies its application-owned metadata before use. */
    @Transactional(readOnly = true)
    public ReviewDraft loadReviewDraft(Long runId, ReviewRevision revision) {
        PrWorkflowRun run = getById(runId);
        ReviewDraft draft = ReviewDraft.read(run.getReviewDraftJson());
        if (!revision.headSha().equals(run.getReviewHeadSha()) || !revision.baseSha().equals(run.getReviewBaseSha())
                || !revision.diffHash().equals(run.getReviewDiffHash()) || draft.version() != run.getReviewDraftVersion()
                || !draft.status().name().equals(run.getReviewDraftStatus())) {
            throw new IllegalStateException("Stored review draft binding does not match");
        }
        return draft;
    }

    /** Atomically seals a diagnostic review and terminates its run without a publishable state. */
    @Transactional
    public void sealReviewShadow(Long runId, ReviewRevision revision, long expected, ReviewDraft draft) {
        if (runId == null || revision == null || draft == null || expected < 0 || draft.version() != expected + 1
                || draft.status() == ReviewDraft.Status.DRAFT) {
            throw new IllegalArgumentException("Invalid shadow seal");
        }
        boolean complete = draft.status() == ReviewDraft.Status.COMPLETE;
        if (complete) draft.render();
        if (runRepository.sealReviewShadow(runId, expected, draft.version(), draft.json(), draft.status().name(),
                complete ? PrWorkflowRunStatus.SHADOW : PrWorkflowRunStatus.FAILED,
                complete ? "Structured review sealed in shadow mode; not published" : "insufficient_context",
                revision.headSha(), revision.baseSha(), revision.diffHash()) != 1) {
            throw new IllegalStateException("Shadow seal lost its active run, version or revision");
        }
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
        int cancelled = 0;
        for (PrWorkflowRun run : active) {
            String existing = run.getSummary() == null ? "" : run.getSummary();
            cancelled += runRepository.finishIfActive(run.getId(), PrWorkflowRunStatus.CANCELLED, now,
                    truncate(("Superseded by a newer run. " + existing).trim(), MAX_SUMMARY_CHARS));
        }
        log.debug("Cancelled {} prior run(s) for bot={} repo={}/{} pr=#{} workflow={}",
                cancelled, botId, repoOwner, repoName, prNumber, workflowKey);
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
        runRepository.finishIfActive(runId, status, Instant.now(), truncate(summary, MAX_SUMMARY_CHARS));
        return getById(runId);
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
