package org.remus.giteabot.prworkflow.readmesync;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agentic {@link PrWorkflow} that keeps project documentation in sync with the
 * code changed in a pull request. When the diff makes the README or other
 * in-scope Markdown documentation inaccurate, incomplete or outdated, the bot
 * updates, creates or deletes the relevant Markdown files (within the
 * configured scope) and posts a short summary comment.
 *
 * <p>Implemented like {@code UnitTestWorkflow} / {@code AgentReviewWorkflow}:
 * per-bot service via {@link ReadmeSyncServiceFactory}, an LLM agent that calls
 * scoped write tools on a checkout of the PR head branch. Category
 * {@link PrWorkflowCategory#DOCS} — opt-in per bot via the workflow-selection
 * UI. No database migration is required (params persist as JSON in
 * {@code workflow_selection_params}).</p>
 *
 * <p>Suite lifecycle mirrors the {@code e2e-test} modes, restricted to the ones
 * meaningful for a DB-migration-free documentation workflow (see
 * {@link #resolveLifecycle}): {@code commit-to-pr} (default) commits the
 * documentation changes straight onto the PR branch; {@code offer-as-pr} opens
 * a follow-up PR; {@code ephemeral} reports the proposed changes without
 * committing.</p>
 */
@Slf4j
@Component
public class ReadmeSyncWorkflow implements PrWorkflow {

    public static final String KEY = "readme-sync";

    static final int DEFAULT_MAX_TOOL_ROUNDS = 12;
    static final int ABSOLUTE_MAX_TOOL_ROUNDS = 30;
    static final String DEFAULT_INCLUDED_FILE_PATTERNS = "README.md, README.*.md, doc/**/*.md, docs/**/*.md";
    static final SuiteLifecycleMode DEFAULT_LIFECYCLE = SuiteLifecycleMode.COMMIT_TO_PR;

    private final ReadmeSyncServiceFactory serviceFactory;
    private final WorkflowSelectionService selectionService;

    public ReadmeSyncWorkflow(ReadmeSyncServiceFactory serviceFactory,
                              @Lazy WorkflowSelectionService selectionService) {
        this.serviceFactory = serviceFactory;
        this.selectionService = selectionService;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "README Sync";
    }

    @Override
    public String description() {
        return "Detects when a pull request's code changes have made the project's Markdown "
                + "documentation inaccurate or outdated, then updates, creates or removes the "
                + "relevant docs (within the configured scope) with an LLM agent and posts a short "
                + "summary. Markdown-only; changed files are constrained to the configured patterns.";
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.DOCS;
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(ReadmeSyncParam.INCLUDED_FILE_PATTERNS,
                        "Documentation file patterns",
                        WorkflowParamField.ParamType.TEXT, false,
                        DEFAULT_INCLUDED_FILE_PATTERNS,
                        "Newline- or comma-separated glob patterns defining the documentation scope. "
                                + "These patterns define BOTH the documentation the workflow reads AND the "
                                + "files it is allowed to create/update/delete. Only Markdown files (*.md / "
                                + "*.markdown) are ever produced. Globs support ** (any path segments), * "
                                + "(within a segment) and ?. Translated variants (e.g. README.de.md) are "
                                + "included when covered by a pattern such as README.*.md."),
                new WorkflowParamField(ReadmeSyncParam.MAX_TOOL_ROUNDS,
                        "Max tool rounds",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TOOL_ROUNDS),
                        "Upper bound on how many explore/write rounds the agent may take (1-"
                                + ABSOLUTE_MAX_TOOL_ROUNDS + "). Higher values allow larger documentation "
                                + "updates at higher token cost."),
                new WorkflowParamField(ReadmeSyncParam.SUITE_LIFECYCLE,
                        "Documentation-change lifecycle",
                        false,
                        DEFAULT_LIFECYCLE.key(),
                        "What to do with the generated documentation changes.",
                        List.of(
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.COMMIT_TO_PR.key(), "Commit to PR",
                                        "Commit the documentation changes directly onto the PR branch (default)."),
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.OFFER_AS_PR.key(), "Offer as PR",
                                        "Open a follow-up PR against the PR branch carrying the documentation changes."),
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.EPHEMERAL.key(), "Report only",
                                        "Report the proposed documentation changes without committing them.")
                        ))
        );
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();

        Map<String, Object> params = bot.getWorkflowConfiguration() == null
                ? Map.of()
                : selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);

        List<String> includePatterns = DocPathGuard.parsePatterns(
                strParam(params, ReadmeSyncParam.INCLUDED_FILE_PATTERNS, DEFAULT_INCLUDED_FILE_PATTERNS));
        int maxToolRounds = clamp(intParam(params, ReadmeSyncParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS),
                1, ABSOLUTE_MAX_TOOL_ROUNDS);
        SuiteLifecycleMode lifecycle = resolveLifecycle(params);
        String guidance = context.hint(PrWorkflowContext.HINT_README_SYNC_GUIDANCE);

        context.requireActive("before running readme-sync workflow");

        ReadmeSyncService.Request request = new ReadmeSyncService.Request(
                context, includePatterns, maxToolRounds, lifecycle, guidance);
        ReadmeSyncService.Result result = serviceFactory.create(bot).run(request);

        return switch (result.status()) {
            case SUCCESS -> WorkflowResult.success(result.summary());
            case SKIPPED -> WorkflowResult.skipped(result.summary());
            case FAILED -> WorkflowResult.failed(result.summary());
        };
    }

    private SuiteLifecycleMode resolveLifecycle(Map<String, Object> params) {
        Object raw = params.get(ReadmeSyncParam.SUITE_LIFECYCLE.key());
        if (raw == null || raw.toString().isBlank()) {
            return DEFAULT_LIFECYCLE;
        }
        try {
            SuiteLifecycleMode mode = SuiteLifecycleMode.fromKey(raw.toString());
            // PROMOTE_ON_MERGE needs persisted-suite deferral machinery this
            // DB-migration-free workflow does not carry — fall back to the default.
            return switch (mode) {
                case COMMIT_TO_PR, OFFER_AS_PR, EPHEMERAL -> mode;
                case PROMOTE_ON_MERGE -> DEFAULT_LIFECYCLE;
            };
        } catch (IllegalArgumentException e) {
            return DEFAULT_LIFECYCLE;
        }
    }

    private String strParam(Map<String, Object> params,
                            org.remus.giteabot.prworkflow.WorkflowParamName name, String fallback) {
        Object raw = params.get(name.key());
        return (raw instanceof String s && !s.isBlank()) ? s : fallback;
    }

    private int intParam(Map<String, Object> params,
                         org.remus.giteabot.prworkflow.WorkflowParamName name, int fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
