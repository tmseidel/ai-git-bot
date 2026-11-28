package org.remus.giteabot.learning.knowledge;

/** A transition in the history of one lesson. */
public enum LessonEventType {
    PROPOSED,
    REVISED,
    ACTIVATED,
    SUPERSEDED,
    DISABLED,
    REJECTED,
    MARKED_STALE,
    /** Feedback a revision cites was retracted or replaced; the lesson needs a maintainer's look. */
    EVIDENCE_WITHDRAWN;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
