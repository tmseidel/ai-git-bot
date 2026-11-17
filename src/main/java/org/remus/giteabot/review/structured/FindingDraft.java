package org.remus.giteabot.review.structured;

import java.util.List;

/**
 * Model-supplied content of one finding. {@code clientKey} is the draft-local upsert key;
 * the server assigns the identity ({@code O1234-F<ordinal>}) the model can never choose.
 */
public record FindingDraft(
        String clientKey,
        ReviewSeverity severity,
        String category,
        String title,
        String explanation,
        String impact,
        String recommendation,
        List<LocationDraft> locations) {
}
