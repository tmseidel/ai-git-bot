package org.remus.giteabot.review.structured;

/**
 * Formats the public references derived from one output reference. Findings are
 * addressed by their stable ordinal, the assessment by a fixed {@code -A} suffix, so a
 * parser can resolve a target without consulting the renderer.
 */
public final class ReviewReferences {

    private ReviewReferences() {
    }

    public static String finding(String outputReference, int ordinal) {
        return outputReference + "-F" + ordinal;
    }

    public static String assessment(String outputReference) {
        return outputReference + "-A";
    }
}
