package org.remus.giteabot.review.structured;

import java.util.List;

/**
 * Inputs for one supplemental snapshot. Everything here is what the review actually ran with; the
 * service bounds it, flags what it had to drop, and stores it next to the review.
 */
public record ReviewSnapshotRequest(
        String outputReference,
        String prTitle,
        String prBody,
        String diff,
        String systemPrompt,
        String provider,
        String model,
        Long workflowConfigurationId,
        String settingsFingerprint,
        String baseSha,
        String headSha,
        String baseRef,
        String headRef,
        String providerRepositoryId,
        List<String> incompleteReasons) {

    public ReviewSnapshotRequest {
        incompleteReasons = incompleteReasons == null ? List.of() : List.copyOf(incompleteReasons);
    }
}
