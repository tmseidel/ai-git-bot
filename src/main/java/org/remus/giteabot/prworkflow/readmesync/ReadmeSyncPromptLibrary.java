package org.remus.giteabot.prworkflow.readmesync;

import org.remus.giteabot.systemsettings.SystemPrompt;

import java.util.List;

/**
 * Central location for the {@link ReadmeSyncAgent} system prompt.
 *
 * <p>Follows the same two-layer split as {@code UnitTestPromptLibrary}:</p>
 * <ol>
 *   <li>An <i>editable</i> role description with a hard-coded fallback here.</li>
 *   <li>A non-editable <i>protocol suffix</i> appended by the software that
 *       pins the configured documentation scope, the Markdown-only constraint
 *       and the {@code doc-write} / {@code doc-delete} tool contract.</li>
 * </ol>
 *
 * <p>The tool-call JSON protocol is additionally appended by
 * {@link org.remus.giteabot.agent.shared.SystemPromptAssembler} from the shared
 * {@link org.remus.giteabot.agent.tools.ToolCatalog}.</p>
 */
public final class ReadmeSyncPromptLibrary {

    private static final String SECTION_SEPARATOR = "\n\n";

    private ReadmeSyncPromptLibrary() {
    }

    /** Built-in editable role description (no operator-editable column today). */
    public static final String DEFAULT_EDITABLE = """
            You are ReadmeSyncAgent, an automated documentation maintainer that
            runs on every opened or synchronised pull request. The user message
            gives you the PR title, body, the unified diff of the code changes
            and the current content of the in-scope documentation files.

            Your job is to detect when the code changes have made the project's
            Markdown documentation inaccurate, incomplete or outdated, and to
            update it so the documentation stays consistent with the code.

            Typical drift to fix:
              * quick-start / setup steps that no longer match current behaviour,
              * outdated README examples or command invocations,
              * newly required setup or usage steps that are missing,
              * documentation sections describing removed functionality.

            Principles:
              * Only change documentation that the code change actually affects —
                do not rewrite unrelated prose or restyle the whole document.
              * Preserve the existing tone, structure and formatting conventions.
              * If a translated documentation variant is in scope and its source
                changed, update the translation too.
              * If the documentation is already accurate, make no changes.""";

    /** Non-editable protocol suffix. Pins scope, the Markdown-only rule and the tools. */
    public static final String PROTOCOL_SUFFIX_TEMPLATE = """
            Documentation scope (include patterns): {patterns}

            You may ONLY create, update or delete Markdown files that match those
            patterns:
              * Use `doc-write` (arguments: path, content) once per file to create
                or overwrite a Markdown file. `content` must be the complete UTF-8
                Markdown source — no placeholders, no TODOs.
              * Use `doc-delete` (argument: path) to remove an obsolete Markdown
                documentation file.
              * Every `path` must be checkout-relative, end in `.md` / `.markdown`
                and match one of the include patterns above. Writes outside this
                scope — including any production code or non-Markdown file — are
                rejected.

            When you have applied every necessary documentation change (or decided
            none are needed), reply with a single final line beginning with `DONE`
            followed by a one-sentence explanation of what you changed or why no
            change was needed.""";

    /**
     * Resolves the agent system prompt by concatenating the editable role
     * description (operator-edited via System settings if present, otherwise the
     * built-in {@link #DEFAULT_EDITABLE}) with the non-editable protocol suffix
     * rendered for the given documentation include patterns.
     */
    public static String systemPrompt(SystemPrompt systemPrompt, List<String> includePatterns) {
        String editable = pick(systemPrompt == null ? null : systemPrompt.getReadmeSyncSystemPrompt(),
                DEFAULT_EDITABLE);
        return editable + SECTION_SEPARATOR + renderProtocol(includePatterns);
    }

    private static String pick(String stored, String fallback) {
        return (stored == null || stored.isBlank()) ? fallback : stored;
    }

    private static String renderProtocol(List<String> includePatterns) {
        String patterns = (includePatterns == null || includePatterns.isEmpty())
                ? "(none configured)"
                : String.join(", ", includePatterns);
        return PROTOCOL_SUFFIX_TEMPLATE.replace("{patterns}", patterns);
    }
}
