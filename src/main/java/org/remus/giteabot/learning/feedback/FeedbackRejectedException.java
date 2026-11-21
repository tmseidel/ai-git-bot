package org.remus.giteabot.learning.feedback;

/**
 * A feedback command that was understood but cannot be carried out. The message is written for the
 * commenter and is the whole reply: the command is consumed here, never forwarded to an AI agent.
 */
public class FeedbackRejectedException extends RuntimeException {

    private final String code;

    public FeedbackRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
