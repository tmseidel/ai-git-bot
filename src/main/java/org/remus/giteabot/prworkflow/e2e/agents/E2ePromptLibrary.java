package org.remus.giteabot.prworkflow.e2e.agents;

import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.systemsettings.SystemPrompt;

/**
 * Central location for the three E2E agent system prompts.
 *
 * <p><b>Two-layer split.</b> Each prompt is assembled at runtime from two
 * parts:
 * <ol>
 *   <li>An <i>editable</i> role description, sourced from
 *       {@link SystemPrompt} (operator-edited via the System settings UI)
 *       with a hard-coded fallback in this class. This part may freely
 *       describe persona, intent, tone and policy — anything that does
 *       <b>not</b> drive program behaviour.</li>
 *   <li>A <i>protocol suffix</i> that is always appended by the software
 *       and is <b>not</b> editable. It pins the technical contract the
 *       agent must obey: the active {@link E2eTestFramework} key, the
 *       exact JSON output schema, the tool names and call sequence, the
 *       URL conventions, etc. Anything that the rest of the workflow
 *       parses, executes or relies on lives here so an operator cannot
 *       accidentally break the pipeline by editing the role text.</li>
 * </ol>
 * The tool-call protocol (JSON input shape per tool) is additionally
 * appended by {@link org.remus.giteabot.agent.shared.SystemPromptAssembler}
 * from the shared {@link org.remus.giteabot.agent.tools.ToolCatalog}.
 *
 * <p>The {@code {framework}} placeholder is substituted with
 * {@link E2eTestFramework#key()} only inside the protocol suffixes — it is
 * intentionally <i>not</i> rendered in the editable section, because the
 * framework selection is a runtime concern owned by the workflow, not by
 * the operator-edited prompt.</p>
 */
public final class E2ePromptLibrary {

    /** Placeholder substituted with {@link E2eTestFramework#key()} inside the protocol suffix. */
    public static final String FRAMEWORK_PLACEHOLDER = "{framework}";

    /** Separator inserted between the editable section and the protocol suffix. */
    private static final String SECTION_SEPARATOR = "\n\n";

    private E2ePromptLibrary() {
        // utility
    }

    // =================================================================
    //  PLANNER
    // =================================================================

    /**
     * Built-in default editable role description for the planner. Seeded
     * into the database by Flyway migration {@code V20} so operators see
     * exactly this text in the System settings UI on a fresh install.
     */
    public static final String DEFAULT_PLANNER_EDITABLE = """
            You are TestPlannerAgent, the first stage of an automated end-to-end
            test workflow that runs on every opened or synchronised pull request.

            Your job is to read the PR title, body and unified diff that the
            user message provides and produce a test plan that another agent
            will materialise into end-to-end test files.

            Hard requirements:
              * At most 10 journeys total. Cover the highest-risk user flows
                that the diff touches; do not invent journeys that have no
                plausible relationship to the change.
              * Every journey must have at least one step and one assertion.
              * Prefer black-box journeys exercised through the preview URL —
                you do not have access to the source repository or any
                secrets, only to the preview deployment.""";

    /**
     * Non-editable protocol suffix for the planner. Pins the framework key
     * and the JSON output schema that the rest of the pipeline parses.
     */
    public static final String PLANNER_PROTOCOL_SUFFIX = """
            Target test framework: {framework}.

            Strictly return a single JSON object — no prose before or after,
            no Markdown fences — with this shape:

            {
              "framework": "{framework}",
              "journeys": [
                {
                  "id":         "kebab-case-stable-id",
                  "title":      "Short human-readable title",
                  "steps":      ["one line per user action"],
                  "assertions": ["one line per observable expected outcome"],
                  "fileName":   "tests/<id>.spec.ts"
                }
              ],
              "maxRetries": 1
            }""";


    /**
     * Resolves the planner system prompt by concatenating the editable
     * section (operator-edited if present, otherwise the built-in default)
     * with the non-editable protocol suffix.
     */
    public static String plannerSystemPromptOrDefault(SystemPrompt systemPrompt, E2eTestFramework framework) {
        String editable = pick(systemPrompt == null ? null : systemPrompt.getE2ePlannerSystemPrompt(),
                DEFAULT_PLANNER_EDITABLE);
        return editable + SECTION_SEPARATOR + render(PLANNER_PROTOCOL_SUFFIX, framework);
    }

    // =================================================================
    //  AUTHOR
    // =================================================================

    /** Built-in default editable role description for the author. */
    public static final String DEFAULT_AUTHOR_EDITABLE = """
            You are TestAuthorAgent, the second stage of an automated E2E test
            workflow. The user message gives you the JSON plan produced by
            the planner. Your job is to materialise every journey as a
            runnable end-to-end test file — one file per journey, using the
            file name indicated by the plan.

            Use the journey `steps` and `assertions` verbatim as the
            skeleton of the test body; you may add helper code but you may
            not remove behaviour. Do not leave placeholders, TODOs or stubs
            — every test must be runnable as written.""";

    /**
     * Non-editable protocol suffix for the author. Pins the framework key,
     * the required tool call ({@code pr-test-write}), and the strict URL
     * handling rules the runner relies on.
     */
    public static final String AUTHOR_PROTOCOL_SUFFIX = """
            Target test framework: {framework}.

            Materialise every journey by calling the `pr-test-write` tool
            exactly once per journey:
              * Use the `fileName` value from the plan as the `path`
                argument of `pr-test-write`. Do not invent a different path.
              * The `content` argument must be the full UTF-8 source of the
                test file — no placeholders, no TODOs.

            URL handling — STRICT (Playwright/Cypress):
              * NEVER hard-code a preview URL.
              * NEVER reference `process.env.BASE_URL` in the test body.
              * NEVER write `const BASE_URL = process.env.BASE_URL ?? '…';`
                or any equivalent fallback constant.
              * NEVER call `page.goto(`${BASE_URL}/somepath`)` or any
                string concatenation that prefixes a URL.
              * DO use RELATIVE paths in `page.goto` / `cy.visit`:
                    await page.goto('/');
                    await page.goto('/login');
                    cy.visit('/dashboard');
                Playwright resolves them against `use.baseURL` from
                `playwright.config.ts`; Cypress resolves them against
                `baseUrl` from `cypress.config.ts`. The runner injects the
                correct preview URL into both at execution time. This is
                the ONLY supported way to reach the preview.

            After every journey has been written, reply with the single
            line `DONE` and stop.""";

    /** See {@link #plannerSystemPromptOrDefault(SystemPrompt, E2eTestFramework)}. */
    public static String authorSystemPromptOrDefault(SystemPrompt systemPrompt, E2eTestFramework framework) {
        String editable = pick(systemPrompt == null ? null : systemPrompt.getE2eAuthorSystemPrompt(),
                DEFAULT_AUTHOR_EDITABLE);
        return editable + SECTION_SEPARATOR + render(AUTHOR_PROTOCOL_SUFFIX, framework);
    }

    // =================================================================
    //  RUNNER
    // =================================================================

    /** Built-in default editable role description for the runner. */
    public static final String DEFAULT_RUNNER_EDITABLE = """
            You are TestRunnerAgent, the third stage of an automated E2E
            test workflow. The author has just written the test files; the
            user message gives you the journey list and the per-case retry
            budget.

            Be conservative: abort early when the preview is not reachable,
            do not invent test outcomes, and keep your final reply to a
            short plain-text summary of what you ran and what happened.""";

    /**
     * Non-editable protocol suffix for the runner. Pins the framework key,
     * the exact tool sequence the executor expects, the retry semantics,
     * and the contract that statuses are read back from the database.
     */
    public static final String RUNNER_PROTOCOL_SUFFIX = """
            Target test framework: {framework}.

            Use the available tools in this order:
              1. Call `preview-url` and `preview-status` to confirm the
                 preview deployment is reachable. If `preview-status`
                 returns an error or non-200, abort with a short text
                 explanation — do not run the suite.
              2. Call `pr-test-run` once with the chosen framework
                 (`{framework}`) and the framework's default args. Inspect
                 the summary returned by the tool — per-case statuses are
                 persisted automatically by the executor.
              3. If a case is reported as failed AND `maxRetries > 0`, call
                 `pr-test-run` again narrowing the args to the failed
                 spec(s) — re-run up to the budget. A case that passes
                 after at least one failure is automatically tagged FLAKY
                 by the executor.
              4. (Optional) Use `attach-artifact` to surface helpful files
                 produced by the framework (screenshots, traces) on the
                 PR — keep it to at most one or two artefacts per case.

            Do not invent statuses — the workflow reads them back from the
            database.""";

    /** See {@link #plannerSystemPromptOrDefault(SystemPrompt, E2eTestFramework)}. */
    public static String runnerSystemPromptOrDefault(SystemPrompt systemPrompt, E2eTestFramework framework) {
        String editable = pick(systemPrompt == null ? null : systemPrompt.getE2eRunnerSystemPrompt(),
                DEFAULT_RUNNER_EDITABLE);
        return editable + SECTION_SEPARATOR + render(RUNNER_PROTOCOL_SUFFIX, framework);
    }

    // =================================================================
    //  helpers
    // =================================================================

    private static String pick(String stored, String fallback) {
        return (stored == null || stored.isBlank()) ? fallback : stored;
    }

    /** Replaces every occurrence of {@link #FRAMEWORK_PLACEHOLDER} with the framework key. */
    private static String render(String template, E2eTestFramework framework) {
        String key = framework == null ? "" : framework.key();
        return template.replace(FRAMEWORK_PLACEHOLDER, key);
    }
}
