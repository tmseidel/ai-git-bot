package org.remus.giteabot.prworkflow.deployment;

/**
 * Enumerates every deployment strategy shipped with the application.
 *
 * <p>The string {@link #key()} is persisted in
 * {@code deployment_targets.strategy_type} and is matched against
 * {@link DeploymentStrategy#typeKey()} at runtime; renaming a value is a
 * breaking schema change and must come with a Flyway migration.</p>
 */
public enum DeploymentStrategyType {

    /** Generic outbound HTTP webhook to an existing CI/CD job (M3). */
    WEBHOOK("WEBHOOK"),

    /** Pre-existing per-PR preview URL (Vercel / Netlify / Render style), M3. */
    STATIC("STATIC"),

    /** Provider-native CI trigger (GitHub Actions / Gitea Actions / GitLab pipelines), M6. */
    CI_ACTION("CI_ACTION"),

    /** Deployment performed by an MCP server tool (M5). */
    MCP("MCP");

    private final String key;

    DeploymentStrategyType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static DeploymentStrategyType fromKey(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Strategy type must not be null");
        }
        for (DeploymentStrategyType type : values()) {
            if (type.key.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown deployment strategy type: " + value);
    }
}
