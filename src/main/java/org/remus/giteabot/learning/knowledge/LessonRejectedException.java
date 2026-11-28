package org.remus.giteabot.learning.knowledge;

import lombok.Getter;

/**
 * A lesson command that was understood but cannot be carried out. The message is written for the
 * commenter and is the whole reply: the command is consumed, never forwarded to an AI agent.
 */
@Getter
public class LessonRejectedException extends RuntimeException {

    private final String code;

    public LessonRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

}
