package org.remus.giteabot.prworkflow.deployment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GitIntegrationService;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.WorkflowRunStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6 scheduled task that drives in-flight {@link DeploymentStrategyType#CI_ACTION}
 * deployments forward.
 *
 * <p>Every {@code prworkflow.ci-action.poll-interval-ms} (default 10 s) the
 * task scans all {@link PrWorkflowRunStatus#WAITING_DEPLOY} rows, picks the
 * ones whose {@code deployment_handle_json} marks them as
 * {@code CI_ACTION}, and queries the matching {@link RepositoryApiClient}
 * for the workflow-run status. When the run reaches a terminal state the
 * poller pushes a {@link DeploymentCallbackNotifier.CallbackResult} so the
 * orchestrator thread blocked on
 * {@link DeploymentOrchestrator#requestDeployment(org.remus.giteabot.prworkflow.PrWorkflowContext)}
 * wakes up immediately — the workflow then continues exactly as if the CI
 * side had POSTed a callback itself.</p>
 *
 * <p>Per-run state is kept in-memory ({@link #lastPolledAt}) so each
 * individual run is only re-polled at its own
 * {@link CiActionTriggerStrategy#CONFIG_POLL_INTERVAL_SECONDS} cadence,
 * even though the scheduler ticks far more frequently. Restarting the
 * process resets the schedule but never duplicates side effects — the
 * worst case is one extra status call right after restart.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CiActionPoller {

    private final PrWorkflowRunRepository runRepository;
    private final DeploymentTargetService deploymentTargetService;
    private final DeploymentCallbackNotifier callbackNotifier;
    private final GitIntegrationService gitIntegrationService;
    private final GiteaClientFactory clientFactory;

    /** Run id → epoch-millis of last status call. */
    private final Map<Long, Long> lastPolledAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${prworkflow.ci-action.poll-interval-ms:10000}",
            initialDelayString = "${prworkflow.ci-action.poll-initial-delay-ms:15000}")
    public void pollAll() {
        var runs = runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY);
        if (runs.isEmpty()) {
            lastPolledAt.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (PrWorkflowRun run : runs) {
            Optional<CiActionTriggerStrategy.Handle> parsed =
                    CiActionTriggerStrategy.parseHandle(run.getDeploymentHandleJson());
            if (parsed.isEmpty()) {
                continue; // not a CI_ACTION run; another strategy will handle it.
            }
            CiActionTriggerStrategy.Handle handle = parsed.get();
            Long last = lastPolledAt.get(run.getId());
            if (last != null && now - last < handle.pollIntervalSeconds() * 1000L) {
                continue;
            }
            lastPolledAt.put(run.getId(), now);
            try {
                pollOne(run, handle);
            } catch (Exception e) {
                log.warn("CiActionPoller failed for run id={}: {}", run.getId(), e.getMessage());
            }
        }
        // GC entries for runs that left WAITING_DEPLOY since the last tick.
        lastPolledAt.keySet().removeIf(id -> runs.stream().noneMatch(r -> r.getId().equals(id)));
    }

    private void pollOne(PrWorkflowRun run, CiActionTriggerStrategy.Handle handle) {
        GitIntegration integration = gitIntegrationService.findById(handle.integrationId()).orElse(null);
        if (integration == null) {
            publish(run.getId(), DeploymentStatus.FAILED, null,
                    "Git integration id=" + handle.integrationId() + " no longer exists");
            return;
        }
        RepositoryApiClient client = clientFactory.getApiClient(integration);
        WorkflowRunStatus status;
        try {
            status = client.getWorkflowRun(handle.owner(), handle.repo(), handle.runId());
        } catch (UnsupportedOperationException e) {
            publish(run.getId(), DeploymentStatus.FAILED, null,
                    "Provider " + integration.getProviderType()
                            + " does not support workflow status lookup");
            return;
        } catch (Exception e) {
            log.debug("CiActionPoller: status lookup failed for run id={} (will retry): {}",
                    run.getId(), e.getMessage());
            return;
        }
        if (!status.isTerminal()) {
            log.debug("CiActionPoller: run id={} workflow {} still {}", run.getId(),
                    handle.runId(), status);
            return;
        }
        if (status == WorkflowRunStatus.COMPLETED_FAILURE || status == WorkflowRunStatus.NOT_FOUND) {
            publish(run.getId(), DeploymentStatus.FAILED, null,
                    "CI workflow run " + handle.runId() + " ended with status " + status);
            return;
        }
        // COMPLETED_SUCCESS — resolve preview URL.
        String previewUrl = resolvePreviewUrl(run, handle, client);
        publish(run.getId(), DeploymentStatus.READY, previewUrl, null);
    }

    private String resolvePreviewUrl(PrWorkflowRun run, CiActionTriggerStrategy.Handle handle,
                                     RepositoryApiClient client) {
        // 1) Try named output (works on GitLab; empty map elsewhere).
        if (handle.previewUrlOutput() != null && !handle.previewUrlOutput().isBlank()) {
            try {
                Map<String, String> outputs = client.getWorkflowRunOutputs(
                        handle.owner(), handle.repo(), handle.runId());
                String fromOutput = outputs.get(handle.previewUrlOutput());
                if (fromOutput != null && !fromOutput.isBlank()) {
                    return fromOutput;
                }
            } catch (Exception e) {
                log.debug("CiActionPoller: outputs lookup failed for run id={}: {}",
                        run.getId(), e.getMessage());
            }
        }
        // 2) Fall back to the deployment-target preview URL template.
        DeploymentTarget target = lookupTargetForRun(run);
        if (target != null && target.getPreviewUrlTemplate() != null
                && !target.getPreviewUrlTemplate().isBlank()) {
            return target.getPreviewUrlTemplate()
                    .replace("{prNumber}", String.valueOf(run.getPrNumber()))
                    .replace("{branch}", "")
                    .replace("{sha}", "");
        }
        // 3) No URL — the workflow succeeded but exposed no preview address.
        //    Surface as READY with null URL; downstream workflows treat this
        //    as success-without-environment and skip preview-dependent steps.
        return null;
    }

    private DeploymentTarget lookupTargetForRun(PrWorkflowRun run) {
        // The bot's currently assigned deployment target may have moved on
        // since the run was started; we accept that risk and use the latest
        // template — the URL pattern is operator-controlled and rarely
        // changes mid-flight.
        try {
            return deploymentTargetService.findAll().stream()
                    .filter(t -> t.getStrategyType() == DeploymentStrategyType.CI_ACTION)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void publish(Long runId, DeploymentStatus status, String previewUrl, String error) {
        var result = new DeploymentCallbackNotifier.CallbackResult(status, previewUrl, error);
        boolean delivered = callbackNotifier.notifyResult(runId, result);
        log.info("CiActionPoller terminal status={} delivered={} for run id={} (preview={})",
                status, delivered, runId, previewUrl);
        lastPolledAt.remove(runId);
        // Touch the run row so dashboards see fresh activity.
        runRepository.findById(runId).ifPresent(r -> {
            // No status mutation here — the orchestrator's resumeFromDeploy()
            // (or DeploymentOrchestrator timeout branch) owns the transition
            // out of WAITING_DEPLOY. We only persist the preview URL so
            // operators see the URL on the dashboard even if the workflow
            // thread is no longer waiting (e.g. process restarted).
            if (status == DeploymentStatus.READY && previewUrl != null
                    && (r.getPreviewUrl() == null || r.getPreviewUrl().isBlank())) {
                r.setPreviewUrl(previewUrl);
                r.setFinishedAt(Instant.now());
                runRepository.save(r);
            }
        });
    }
}

