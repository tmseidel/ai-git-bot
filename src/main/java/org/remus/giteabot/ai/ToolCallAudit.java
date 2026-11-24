package org.remus.giteabot.ai;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Times a tool call requested by the model and reports it to the current {@link AuditRun}, where
 * it is attached to the run's latest AI interaction.
 *
 * <p>Calls made where no run is open are not recorded. That includes the nested calls of an
 * {@code execute-code} program: they run on the sandbox's bridge thread, which does not see the
 * agent thread's {@link AiAuditContext}, so the program is recorded as one call whose duration
 * covers them.</p>
 */
@Slf4j
public final class ToolCallAudit {

    /** Source of the tools the bot implements itself. */
    public static final String BUILT_IN = "built-in";

    private static final String TEXT_ERROR_PREFIX = "ERROR: ";

    private ToolCallAudit() {
    }

    /**
     * Runs {@code call} and records it.
     *
     * @param source    MCP server name, or {@link #BUILT_IN}
     * @param toolName  the tool's name as the model sees it
     * @param arguments the call's arguments as JSON
     * @param call      the tool call itself
     * @param errorOf   the error message of a failed result, or {@code null} for a success
     */
    public static <T> T record(String source, String toolName, String arguments,
                               Supplier<T> call, Function<T, String> errorOf) {
        String effectiveSource = source != null ? source : BUILT_IN;
        AuditRun run = AiAuditContext.currentRun();
        if (run == null || run.getSink() == null || run.getInteractionId() == null) {
            return call.get();
        }

        long start = System.nanoTime();
        T result;
        String error;
        try {
            result = call.get();
            error = errorOf.apply(result);
        } catch (RuntimeException | Error e) {
            report(run, effectiveSource, toolName, start, e.getMessage() != null ? e.getMessage()
                    : e.getClass().getName(), arguments);
            throw e;
        }
        report(run, effectiveSource, toolName, start, error, arguments);
        return result;
    }

    /**
     * The error of a tool result in the workflow executors' text convention (a result starting
     * with {@code "ERROR: "}), or {@code null} for a success.
     */
    public static String errorOfTextResult(String result) {
        if (result != null && result.startsWith(TEXT_ERROR_PREFIX)) {
            return result.substring(TEXT_ERROR_PREFIX.length());
        }
        return null;
    }

    /** Renders tool arguments as JSON for the audit log. */
    public static String json(Object arguments) {
        try {
            return AgentJackson.mapper().writeValueAsString(arguments);
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }

    private static void report(AuditRun run, String source, String toolName, long startNanos,
                               String error, String arguments) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        try {
            run.getSink().toolCallFinished(run, new AuditRun.ToolCallRecord(source,
                    toolName, durationMs, error == null, error, arguments));
        } catch (RuntimeException e) {
            log.warn("Failed to record tool call '{}': {}", toolName, e.getMessage());
        }
    }
}
