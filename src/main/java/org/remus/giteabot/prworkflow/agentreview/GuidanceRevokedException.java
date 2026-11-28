package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.prworkflow.WorkflowCancelledException;

/**
 * An agentic-review run cancelled because learned guidance it already received was revoked while it
 * ran. Unlike a run superseded by a newer one, the person who asked is told: the review or answer
 * was withheld for a reason they can act on — asking again runs with the current lessons.
 */
public class GuidanceRevokedException extends WorkflowCancelledException {

    private final String reason;

    public GuidanceRevokedException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    /** Why the guidance no longer holds, e.g. {@code L17@1 is no longer active}. */
    public String reason() {
        return reason;
    }
}
