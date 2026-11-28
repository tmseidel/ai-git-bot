package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Instance settings of the Review quality page. */
@Data
@Component
@ConfigurationProperties(prefix = "review-quality")
public class ReviewQualityProperties {

    /** Hard ceiling of {@link #minSample}. */
    public static final int MAX_MIN_SAMPLE = 1_000;

    /**
     * Whether the page shows figures per pull-request author. Off by default: the figures are meant
     * for coaching and onboarding, and an installation decides deliberately to show them.
     */
    private boolean authorMetrics = false;

    /**
     * Smallest number of items a rate is computed from; below it the page shows "–" instead of a
     * misleading percentage. At least 1, at most {@value #MAX_MIN_SAMPLE}.
     */
    private int minSample = 10;

    /** Authors with fewer reviewed pull requests than this are grouped as "others". */
    private int authorMinPrs = 5;

    public int effectiveMinSample() {
        return Math.max(1, Math.min(minSample, MAX_MIN_SAMPLE));
    }

    public int effectiveAuthorMinPrs() {
        return Math.max(1, authorMinPrs);
    }
}
