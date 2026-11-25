package org.remus.giteabot.learning.context;

import org.remus.giteabot.learning.knowledge.LessonReference;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The learned guidance selected for one agentic-review run: which exact lesson revisions, the text
 * block the model receives, and why other active lessons were left out.
 *
 * <p>Immutable. The {@link #text()} is rendered once at selection and never re-rendered, so the
 * {@link #contentHash()} recorded with every usage proves exactly what the model was given. An
 * empty bundle carries no text at all — not a header without lessons — so a run without lessons is
 * byte-for-byte the run it was before lesson retrieval existed.</p>
 *
 * @param status          why the bundle has, or has no, lessons
 * @param lessons         the selected revisions, in the order they appear in {@link #text()}
 * @param excluded        active lessons that were considered and left out, with the reason
 * @param text            the guidance block, or {@code null} when no lesson was selected
 * @param contentHash     SHA-256 of {@link #text()}, or {@code null} without text
 * @param estimatedTokens token estimate of {@link #text()}, {@code 0} without text
 */
public record LessonBundle(Status status, List<SelectedLesson> lessons, List<Exclusion> excluded,
                           String text, String contentHash, long estimatedTokens) {

    /** Why a bundle has, or has no, lessons. Stored with the output snapshot. */
    public enum Status {
        /** At least one lesson was selected. */
        SELECTED,
        /** Retrieval ran; no active lesson applies to this change. */
        NONE,
        /** The bot's learning mode does not use lessons; nothing was queried. */
        LEARNING_OFF,
        /** Not an agentic review; nothing was queried. */
        NOT_APPLICABLE,
        /** Retrieval failed; the review runs without learned guidance and is no lesson's evidence. */
        UNAVAILABLE
    }

    /**
     * One supplied revision.
     *
     * @param reference    the lesson ({@code L17})
     * @param revision     the revision number supplied
     * @param revisionId   database identity of that revision, for the usage record
     * @param contentHash  the revision's own wording hash
     * @param paths        its path scope; empty means repository-wide
     * @param evidence     short references to the feedback it rests on ({@code FB31 on O12-F2, PR #4})
     * @param reason       why it was selected, for the usage record
     */
    public record SelectedLesson(Long lessonId, String reference, int revision, Long revisionId,
                                 String contentHash, String text, List<String> paths,
                                 String category, List<String> evidence, String reason) {

        public SelectedLesson {
            paths = paths == null ? List.of() : List.copyOf(paths);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public String revisionReference() {
            return LessonReference.revision(reference, revision);
        }
    }

    /** An active lesson that was considered and left out of this run. */
    public record Exclusion(String reference, String reason) {
    }

    public LessonBundle {
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
    }

    /** A bundle without guidance. */
    public static LessonBundle empty(Status status, List<Exclusion> excluded) {
        return new LessonBundle(status, List.of(), excluded, null, null, 0);
    }

    public static LessonBundle empty(Status status) {
        return empty(status, List.of());
    }

    /** Whether the model receives any learned guidance. */
    public boolean hasGuidance() {
        return !lessons.isEmpty() && text != null;
    }

    /** {@code L17@1, L18@2} — empty without lessons. */
    public String revisionReferences() {
        return lessons.stream().map(SelectedLesson::revisionReference)
                .collect(Collectors.joining(", "));
    }
}
