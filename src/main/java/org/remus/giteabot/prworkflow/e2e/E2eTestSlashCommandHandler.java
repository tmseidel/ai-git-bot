package org.remus.giteabot.prworkflow.e2e;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and acts on the M4 / wave-2 slash commands an operator can post
 * on a pull-request to interact with the {@link E2ETestWorkflow}:
 *
 * <ul>
 *   <li>{@code @bot rerun-tests} – re-trigger the {@code e2e-test} workflow
 *       on the current PR (currently a full re-run; a future optimisation
 *       can skip planner+author and only re-run the runner against the
 *       previously persisted {@link PrTestSuite}).</li>
 *   <li>{@code @bot regenerate-tests [feedback...]} – re-trigger the full
 *       workflow. The optional feedback is logged on the new
 *       {@code PrWorkflowRun} so operators can correlate it; the planner
 *       agent will pick it up once the context-hint plumbing lands.</li>
 * </ul>
 *
 * <p>The handler is deliberately conservative — if the bot has no
 * {@code e2e-test} workflow enabled in its {@code WorkflowConfiguration},
 * the command is ignored (returns {@code false}) so the regular
 * {@code CodeReviewService} comment-as-prompt fallback still runs.</p>
 */
@Slf4j
@Service
public class E2eTestSlashCommandHandler {

    /**
     * Permissive matcher: allows the bot mention to be the literal
     * {@code @bot} marker OR any actual bot alias (configured per bot via
     * {@link Bot#getName()}), followed by either {@code rerun-tests} or
     * {@code regenerate-tests} as the first whitespace-delimited token.
     * Any trailing text after {@code regenerate-tests} is captured as
     * feedback (group 2).
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(rerun-tests|regenerate-tests)\\b\\s*(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;

    public E2eTestSlashCommandHandler(PrWorkflowOrchestrator orchestrator,
                                      WorkflowSelectionService selectionService) {
        this.orchestrator = orchestrator;
        this.selectionService = selectionService;
    }

    /**
     * @return {@code true} when the comment was recognised as an E2E slash
     *         command and dispatched (regardless of the dispatched run's
     *         outcome); {@code false} when the comment is something else and
     *         the caller must continue with its default handling.
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload) {
        if (bot == null || payload == null || payload.getComment() == null) {
            return false;
        }
        String body = payload.getComment().getBody();
        if (body == null || body.isBlank()) {
            return false;
        }
        Matcher matcher = COMMAND_PATTERN.matcher(body);
        if (!matcher.find()) {
            return false;
        }
        String verb = matcher.group(1).toLowerCase(Locale.ROOT);
        String feedback = matcher.group(2) == null ? "" : matcher.group(2).trim();

        if (!isE2eEnabledOnBot(bot)) {
            log.info("[Bot '{}'] E2E slash command '{}' ignored — workflow not enabled on configuration",
                    bot.getName(), verb);
            return false;
        }

        log.info("[Bot '{}'] E2E slash command '{}' detected (feedback='{}'), dispatching e2e-test workflow",
                bot.getName(), verb, abbreviate(feedback, 80));
        try {
            Map<String, String> hints = feedback.isBlank()
                    ? Map.of()
                    : Map.of(PrWorkflowContext.HINT_E2E_FEEDBACK, feedback);
            orchestrator.run(bot, payload, E2ETestWorkflow.KEY, hints);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] E2E slash command '{}' dispatch failed: {}",
                    bot.getName(), verb, e.getMessage(), e);
        }
        return true;
    }

    private boolean isE2eEnabledOnBot(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return false;
        }
        try {
            return selectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .contains(E2ETestWorkflow.KEY);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] E2E enabled-check failed: {}", bot.getName(), e.getMessage());
            return false;
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}




