package org.remus.giteabot.systemsettings;

public record McpToolSelectionRow(
        String qualifiedName,
        String serverName,
        String toolName,
        String title,
        String description,
        boolean selected
) {
}

