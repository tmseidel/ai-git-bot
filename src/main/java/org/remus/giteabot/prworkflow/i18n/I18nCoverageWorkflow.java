package org.remus.giteabot.prworkflow.i18n;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamName;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agentic {@link PrWorkflow} that keeps i18n translation coverage consistent
 * across locale files when a pull request changes user-facing strings. When a
 * non-baseline locale file is missing keys the baseline defines (added/changed
 * strings) or still carries keys the baseline deleted, the bot drafts the
 * missing translations and removes the stale keys, then posts a short summary
 * comment.
 *
 * <p>Implemented like {@code ReadmeSyncWorkflow}: per-bot service via
 * {@link I18nCoverageServiceFactory}, an LLM agent that calls scoped write tools
 * on a checkout of the PR head branch. Category {@link PrWorkflowCategory#DOCS}
 * — opt-in per bot via the workflow-selection UI. No database migration is
 * required (params persist as JSON in {@code workflow_selection_params}).</p>
 *
 * <p>Suite lifecycle mirrors the {@code e2e-test} modes, restricted to the ones
 * meaningful for a DB-migration-free workflow (see {@link #resolveLifecycle}):
 * {@code commit-to-pr} (default) commits the translation changes straight onto
 * the PR branch; {@code offer-as-pr} opens a follow-up PR; {@code ephemeral}
 * reports the proposed changes without committing.</p>
 */
@Slf4j
@Component
public class I18nCoverageWorkflow implements PrWorkflow {

    public static final String KEY = "i18n-coverage";

    static final int DEFAULT_MAX_TOOL_ROUNDS = 14;
    static final int ABSOLUTE_MAX_TOOL_ROUNDS = 30;
    static final String DEFAULT_INCLUDED_FILE_PATTERNS =
            "messages_*.properties, i18n/*.json, **/messages_*.properties, **/i18n/*.json";
    /**
     * Blank by default (empty string, NOT "en"). A blank baseline is a
     * meaningful value — "auto-detect the reference file per bundle family" —
     * so it must be persistable. Giving this field a non-blank default would
     * make {@code WorkflowParamsValidator} silently substitute that default
     * whenever the operator clears the field, so a blank choice could never be
     * saved (it would revert on the next page load).
     */
    static final String DEFAULT_BASELINE_LOCALE = "";
    static final SuiteLifecycleMode DEFAULT_LIFECYCLE = SuiteLifecycleMode.COMMIT_TO_PR;

    private final I18nCoverageServiceFactory serviceFactory;
    private final WorkflowSelectionService selectionService;

    public I18nCoverageWorkflow(I18nCoverageServiceFactory serviceFactory,
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
        return "i18n Coverage";
    }

    @Override
    public String description() {
        return "Detects when a pull request's i18n changes (added keys, changed values, deleted "
                + "keys) are not consistently reflected across locale files, then drafts the missing "
                + "translations per locale with an LLM agent against a configurable baseline locale "
                + "and posts a short summary. Supports messages_*.properties and i18n/*.json files.";
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.DOCS;
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(I18nCoverageParam.INCLUDED_FILE_PATTERNS,
                        "i18n file patterns",
                        WorkflowParamField.ParamType.TEXT, false,
                        DEFAULT_INCLUDED_FILE_PATTERNS,
                        "Newline- or comma-separated glob patterns identifying the i18n locale files. "
                                + "These patterns define BOTH the files the workflow inspects AND the files "
                                + "it is allowed to create/update/delete. Only locale files (*.properties / "
                                + "*.json) are ever produced. Globs support ** (any path segments), * "
                                + "(within a segment) and ?. Examples: messages_*.properties, i18n/*.json."),
                new WorkflowParamField(I18nCoverageParam.BASELINE_LOCALE,
                        "Baseline locale",
                        WorkflowParamField.ParamType.STRING, false,
                        DEFAULT_BASELINE_LOCALE,
                        "Optional. The reference locale whose keys/values/deletions the other locale "
                                + "files are compared against (e.g. en, en_US). Every non-baseline locale "
                                + "file that is missing baseline keys gets drafted translations; keys the "
                                + "baseline no longer defines are removed. Leave blank to auto-detect the "
                                + "baseline per bundle family — the implicit-default (suffix-less) file such "
                                + "as messages.properties is used when present, otherwise the first locale "
                                + "file. A blank value is saved as-is and will not revert to a default."),
                new WorkflowParamField(I18nCoverageParam.MAX_TOOL_ROUNDS,
                        "Max tool rounds",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TOOL_ROUNDS),
                        "Upper bound on how many explore/write rounds the agent may take (1-"
                                + ABSOLUTE_MAX_TOOL_ROUNDS + "). Higher values allow larger translation "
                                + "updates at higher token cost."),
                new WorkflowParamField(I18nCoverageParam.SUITE_LIFECYCLE,
                        "Translation-change lifecycle",
                        false,
                        DEFAULT_LIFECYCLE.key(),
                        "What to do with the generated translation changes.",
                        List.of(
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.COMMIT_TO_PR.key(), "Commit to PR",
                                        "Commit the translation changes directly onto the PR branch (default)."),
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.OFFER_AS_PR.key(), "Offer as PR",
                                        "Open a follow-up PR against the PR branch carrying the translation changes."),
                                new WorkflowParamField.EnumOption(SuiteLifecycleMode.EPHEMERAL.key(), "Report only",
                                        "Report the proposed translation changes without committing them.")
                        ))
        );
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();

        Map<String, Object> params = bot.getWorkflowConfiguration() == null
                ? Map.of()
                : selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);

        List<String> includePatterns = I18nPathGuard.parsePatterns(
                strParam(params, I18nCoverageParam.INCLUDED_FILE_PATTERNS, DEFAULT_INCLUDED_FILE_PATTERNS));
        String baselineLocale = strParam(params, I18nCoverageParam.BASELINE_LOCALE, DEFAULT_BASELINE_LOCALE);
        int maxToolRounds = clamp(intParam(params, I18nCoverageParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS),
                1, ABSOLUTE_MAX_TOOL_ROUNDS);
        SuiteLifecycleMode lifecycle = resolveLifecycle(params);
        String guidance = context.hint(PrWorkflowContext.HINT_I18N_COVERAGE_GUIDANCE);

        context.requireActive("before running i18n-coverage workflow");

        I18nCoverageService.Request request = new I18nCoverageService.Request(
                context, includePatterns, baselineLocale, maxToolRounds, lifecycle, guidance);
        I18nCoverageService.Result result = serviceFactory.create(bot).run(request);

        return switch (result.status()) {
            case SUCCESS -> WorkflowResult.success(result.summary());
            case SKIPPED -> WorkflowResult.skipped(result.summary());
            case FAILED -> WorkflowResult.failed(result.summary());
        };
    }

    private SuiteLifecycleMode resolveLifecycle(Map<String, Object> params) {
        Object raw = params.get(I18nCoverageParam.SUITE_LIFECYCLE.key());
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

    private String strParam(Map<String, Object> params, WorkflowParamName name, String fallback) {
        Object raw = params.get(name.key());
        return (raw instanceof String s && !s.isBlank()) ? s : fallback;
    }

    private int intParam(Map<String, Object> params, WorkflowParamName name, int fallback) {
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
