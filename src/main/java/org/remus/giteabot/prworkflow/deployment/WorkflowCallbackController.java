package org.remus.giteabot.prworkflow.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunService;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Inbound HTTP endpoints used by deployment strategies that
 * {@linkplain DeploymentStrategy#awaitsCallback() await an asynchronous
 * callback}. Both endpoints authenticate the caller via the per-run
 * {@code callbackSecret} embedded in the URL; mutating endpoints
 * additionally HMAC-verify the body when a signature header is present.
 *
 * <h2>Status endpoint</h2>
 * <pre>
 * POST /api/workflow-callback/{runId}/{secret}
 * Content-Type: application/json
 * X-AI-Bot-Signature: sha256=&lt;hex&gt;        (optional but recommended)
 *
 * { "status": "READY|FAILED",
 *   "previewUrl": "https://pr-42.preview.acme.io",
 *   "errorMessage": "..." }
 * </pre>
 *
 * <h2>Log streaming endpoint</h2>
 * <pre>
 * POST /api/workflow-log/{runId}/{secret}
 * Content-Type: text/plain
 *
 * &lt;raw log chunk, truncated to 4 KB on the server&gt;
 * </pre>
 *
 * <p>Replay protection: the status endpoint is a no-op once the run is in a
 * terminal status, so a re-delivered {@code SUCCESS} cannot flip a
 * {@code FAILED} run back to green.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class WorkflowCallbackController {

    static final int MAX_LOG_CHUNK_CHARS = 4 * 1024;
    public static final String SIGNATURE_HEADER = "X-AI-Bot-Signature";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PrWorkflowRunService runService;
    private final DeploymentCallbackNotifier callbackNotifier;

    public WorkflowCallbackController(PrWorkflowRunService runService,
                                      DeploymentCallbackNotifier callbackNotifier) {
        this.runService = runService;
        this.callbackNotifier = callbackNotifier;
    }

    @PostMapping("/workflow-callback/{runId}/{secret}")
    public ResponseEntity<String> callback(@PathVariable Long runId,
                                           @PathVariable String secret,
                                           @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                           @RequestBody(required = false) String body) {
        PrWorkflowRun run = lookupAndAuthenticate(runId, secret);
        if (run == null) {
            return unauthorized();
        }
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            log.info("Dropping callback for already-terminal run id={} status={}", runId, run.getStatus());
            return ResponseEntity.status(409).body("Run is already terminal");
        }
        if (signature != null && !signature.isBlank() && !verifySignature(signature, body, run.getCallbackSecret())) {
            log.warn("Rejected workflow callback for run id={} - signature mismatch", runId);
            return unauthorized();
        }

        JsonNode payload;
        try {
            payload = body == null || body.isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON body: " + e.getMessage());
        }

        String statusRaw = textOrNull(payload, "status");
        if (statusRaw == null) {
            return ResponseEntity.badRequest().body("Missing 'status'");
        }
        DeploymentStatus status;
        try {
            status = DeploymentStatus.valueOf(statusRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Unknown 'status' value: " + statusRaw);
        }
        String previewUrl = textOrNull(payload, "previewUrl");
        String errorMessage = textOrNull(payload, "errorMessage");

        if (status == DeploymentStatus.READY) {
            // Persist the state transition WAITING_DEPLOY → RUNNING (and the
            // preview URL) unconditionally, so the DB is consistent even if
            // no orchestrator thread is waiting on this instance (restart,
            // multi-instance deployment, callback arriving just after the
            // orchestrator's await timeout). resumeFromDeploy is a no-op
            // when the run is not currently WAITING_DEPLOY.
            runService.resumeFromDeploy(runId, previewUrl);
        }
        if (status == DeploymentStatus.FAILED || status == DeploymentStatus.REJECTED) {
            // Persist the failure even if no orchestrator thread is waiting.
            runService.complete(runId, PrWorkflowRunStatus.FAILED,
                    "Deployment callback reported " + status + ": " + (errorMessage == null ? "" : errorMessage));
        }

        boolean delivered = callbackNotifier.notifyResult(runId,
                new DeploymentCallbackNotifier.CallbackResult(status, previewUrl, errorMessage));
        log.info("Workflow callback for run id={} status={} preview={} delivered-to-thread={}",
                runId, status, previewUrl, delivered);
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/workflow-log/{runId}/{secret}")
    public ResponseEntity<String> log(@PathVariable Long runId,
                                      @PathVariable String secret,
                                      @RequestBody(required = false) String body) {
        PrWorkflowRun run = lookupAndAuthenticate(runId, secret);
        if (run == null) {
            return unauthorized();
        }
        String chunk = body == null ? "" : body;
        if (chunk.length() > MAX_LOG_CHUNK_CHARS) {
            chunk = chunk.substring(0, MAX_LOG_CHUNK_CHARS - 3) + "...";
        }
        runService.appendStep(runId, "deployment-log", "INFO", chunk);
        return ResponseEntity.ok("OK");
    }

    private PrWorkflowRun lookupAndAuthenticate(Long runId, String secret) {
        PrWorkflowRun run;
        try {
            run = runService.getById(runId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (run.getCallbackSecret() == null
                || !constantTimeEquals(run.getCallbackSecret(), secret)) {
            return null;
        }
        return run;
    }

    private static ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(401).body("Unauthorized");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static boolean verifySignature(String headerValue, String body, String secret) {
        if (headerValue == null || secret == null) {
            return false;
        }
        String stripped = headerValue.startsWith("sha256=") ? headerValue.substring(7) : headerValue;
        try {
            String expected = WebhookTriggerStrategy.hmacSha256Hex(secret, body == null ? "" : body);
            return constantTimeEquals(expected, stripped);
        } catch (Exception e) {
            return false;
        }
    }
}

