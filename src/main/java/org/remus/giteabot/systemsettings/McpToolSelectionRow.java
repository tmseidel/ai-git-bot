package org.remus.giteabot.systemsettings;

public record McpToolSelectionRow(
        String qualifiedName,
        String serverName,
        String toolName,
        String title,
        String description,
        boolean selected,
        boolean reviewReadOnlyApproved
) {
    /** Legacy selections do not imply read-only approval. */
    public McpToolSelectionRow(String qualifiedName, String serverName, String toolName,
                               String title, String description, boolean selected) {
        this(qualifiedName, serverName, toolName, title, description, selected, false);
    }
}
