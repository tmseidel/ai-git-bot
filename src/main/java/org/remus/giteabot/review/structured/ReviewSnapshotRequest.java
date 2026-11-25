package org.remus.giteabot.review.structured;

import java.util.List;

/**
 * Inputs for one supplemental snapshot. Everything here is what the review actually ran with; the
 * service bounds it, flags what it had to drop, and stores it next to the review.
 *
 * @param lessonSelection   status of the learned guidance (see {@code LessonBundle.Status}), or
 *                          {@code null} when not recorded
 * @param lessonRevisions   the lesson revisions supplied, {@code L17@1, L18@2}, or {@code null}
 * @param lessonContextHash hash of the guidance block supplied, or {@code null}
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
        List<String> incompleteReasons,
        String lessonSelection,
        String lessonRevisions,
        String lessonContextHash) {

    public ReviewSnapshotRequest {
        incompleteReasons = incompleteReasons == null ? List.of() : List.copyOf(incompleteReasons);
    }

    /** A snapshot without lesson information. */
    public ReviewSnapshotRequest(String outputReference, String prTitle, String prBody, String diff,
                                 String systemPrompt, String provider, String model,
                                 Long workflowConfigurationId, String settingsFingerprint,
                                 String baseSha, String headSha, String baseRef, String headRef,
                                 String providerRepositoryId, List<String> incompleteReasons) {
        this(outputReference, prTitle, prBody, diff, systemPrompt, provider, model,
                workflowConfigurationId, settingsFingerprint, baseSha, headSha, baseRef, headRef,
                providerRepositoryId, incompleteReasons, null, null, null);
    }
}
