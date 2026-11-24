package org.remus.giteabot.learning.knowledge;

/**
 * Lifecycle of a lesson revision, and the summary state of a lesson.
 *
 * <p>A revision moves {@code CANDIDATE → ACTIVE → DISABLED | STALE | SUPERSEDED}; a candidate that
 * is turned down becomes {@code REJECTED}. Text is never edited in place: a change is a new
 * candidate revision, so an earlier review's guidance stays explainable.</p>
 */
public enum LessonState {

    /** Written, not used. Activation needs a maintainer naming this exact revision. */
    CANDIDATE,

    /** The revision later agentic reviews may receive. At most one per lesson. */
    ACTIVE,

    /** Switched off by a maintainer; history and evidence are kept. */
    DISABLED,

    /** Its supporting source or recheck date no longer holds; it must be revised before reuse. */
    STALE,

    /** Replaced by a newer revision of the same lesson. */
    SUPERSEDED,

    /** A candidate a maintainer turned down. */
    REJECTED;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
