package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Instance-wide hard limits for learning evidence. Workflow preferences can narrow what is
 * collected; they can never raise these ceilings.
 */
@Data
@Component
@ConfigurationProperties(prefix = "learning")
public class LearningProperties {

    /**
     * Hard cap on the characters stored in one {@code workflow_output_snapshots} row. The snapshot is
     * evidence for later analysis, not a replay archive: content beyond the cap is dropped and the
     * row is marked incomplete rather than silently shortened.
     */
    private int snapshotMaxChars = 200_000;

    /**
     * How long bulk snapshots are kept, in days. The review record, its findings and the feedback
     * that references them are kept independently — only the bulky replay evidence expires.
     */
    private int snapshotRetentionDays = 90;
}
