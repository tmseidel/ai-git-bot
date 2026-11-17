package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Presentation of the agentic-review comment itself, as opposed to what it reports.
 *
 * <p>The footer is instance-wide branding rendered as the last line of every published review. It is
 * Markdown by design (links, emphasis), so it is emitted verbatim apart from the blank-line
 * normalization the renderer applies to every block. An empty value publishes reviews without a
 * footer.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "review.comment")
public class ReviewCommentProperties {

    /** Default footer of a published review; overridable through {@code REVIEW_COMMENT_FOOTER}. */
    public static final String DEFAULT_FOOTER = "*Agentic review by [AI Git Bot](https://gitbot.eu)*";

    /** Default footer of a follow-up answer; overridable through {@code REVIEW_COMMENT_CLARIFICATION_FOOTER}. */
    public static final String DEFAULT_CLARIFICATION_FOOTER =
            "*Agentic review follow-up by [AI Git Bot](https://gitbot.eu)*";

    private String footer = DEFAULT_FOOTER;

    /**
     * Footer of the answer to a follow-up question. Separate from the review footer because it
     * describes a different thing — a read-only answer, not a review — and may carry a different
     * link or disclosure. Empty publishes the answer without a footer.
     */
    private String clarificationFooter = DEFAULT_CLARIFICATION_FOOTER;
}
