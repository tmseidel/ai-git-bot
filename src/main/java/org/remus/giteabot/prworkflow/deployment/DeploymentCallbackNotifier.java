package org.remus.giteabot.prworkflow.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process notification channel that lets the inbound callback controller
 * wake the orchestrator thread that is currently blocked on
 * {@link DeploymentOrchestrator#awaitCallback(Long, long)}.
 *
 * <p>One {@link SynchronousQueue} per active run id. The producer
 * ({@code WorkflowCallbackController}) calls {@link #notifyResult}; the
 * consumer (the orchestrator thread) calls {@link #awaitResult} with a
 * timeout. Limitations:</p>
 * <ul>
 *     <li>Single-instance only — a callback delivered to instance B cannot
 *         wake a thread on instance A. Documented in
 *         {@code doc/PR_WORKFLOWS.md}; multi-instance deployments need
 *         shared state (Redis pub/sub or DB polling).</li>
 *     <li>If no consumer is waiting when a callback arrives, the result is
 *         dropped (the run status update via
 *         {@link org.remus.giteabot.prworkflow.PrWorkflowRunService} still
 *         persists, so the next webhook resync will see it).</li>
 * </ul>
 */
@Slf4j
@Component
public class DeploymentCallbackNotifier {

    private final Map<Long, SynchronousQueue<CallbackResult>> queues = new ConcurrentHashMap<>();

    /**
     * Blocks for at most {@code timeoutMillis} waiting for a callback for
     * {@code runId}. Returns {@code null} on timeout.
     */
    public CallbackResult awaitResult(Long runId, long timeoutMillis) throws InterruptedException {
        SynchronousQueue<CallbackResult> queue = queues.computeIfAbsent(runId, k -> new SynchronousQueue<>());
        try {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            queues.remove(runId, queue);
        }
    }

    /**
     * Wakes any thread blocked in {@link #awaitResult} for the same run id.
     * Returns {@code true} if a consumer accepted the result, {@code false}
     * if there was none (the caller should still persist the status change).
     */
    public boolean notifyResult(Long runId, CallbackResult result) {
        SynchronousQueue<CallbackResult> queue = queues.get(runId);
        if (queue == null) {
            log.debug("No orchestrator thread waiting on callback for run id={}", runId);
            return false;
        }
        return queue.offer(result);
    }

    public record CallbackResult(DeploymentStatus status, String previewUrl, String errorMessage) {
    }
}
