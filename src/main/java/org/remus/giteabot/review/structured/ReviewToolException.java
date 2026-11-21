package org.remus.giteabot.review.structured;

import lombok.Getter;

/**
 * A review-tool call that was refused before it could mutate the draft, and whose refusal must
 * not be recorded as a receipt (so a later retry still has its chance).
 */
@Getter
public class ReviewToolException extends RuntimeException {

    public enum Code {
        /** The run was cancelled between rounds; nothing further is written. */
        CANCELLED,
        /** The operation id was already used with different arguments. */
        OPERATION_CONFLICT
    }

    private final Code code;

    public ReviewToolException(Code code, String message) {
        super(message);
        this.code = code;
    }
}
