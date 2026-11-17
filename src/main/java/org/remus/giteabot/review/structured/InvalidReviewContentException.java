package org.remus.giteabot.review.structured;

import lombok.Getter;

/**
 * Rejected review content. {@code field} names the offending input so a tool response can
 * point the model at exactly what to repair instead of asking it to retry blindly.
 */
@Getter
public class InvalidReviewContentException extends RuntimeException {

    private final String field;

    public InvalidReviewContentException(String field, String message) {
        super(message);
        this.field = field;
    }
}
