package org.remus.giteabot.systemsettings;

/**
 * Row in the bot-tool selection UI. Mirrors the shape of
 * {@link McpToolSelectionRow} so the admin templates can stay analogous.
 *
 * @param toolName    stable lower-case identifier from the {@code ToolCatalog}
 * @param toolKind    one of {@code CONTEXT|FILE|VALIDATION|REPOSITORY}
 * @param description human-readable description used for the LLM (best effort)
 * @param selected    whether this tool is enabled in the configuration
 */
public record BotToolSelectionRow(
        String toolName,
        String toolKind,
        String description,
        boolean selected
) {
}
