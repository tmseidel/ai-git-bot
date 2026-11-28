package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.learning.context.LessonBundle;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The learned guidance one agentic-review run received, and the check that it still holds.
 *
 * <p>Messages already sent to the model cannot be taken back. So when a supplied lesson is
 * disabled, replaced or invalidated — or learning is switched off — while the run is in flight, the
 * run must not publish what it produced: {@link #recheck()} is asked at every round boundary and
 * right before publication, and once it reports a revocation it keeps reporting it.</p>
 *
 * <p>A check that fails (database unreachable) cannot tell either way; that is logged and treated as
 * "still holds", like an unreadable head is treated before publication — refusing every review on a
 * transient error would be worse than finishing one whose guidance was valid when it started.</p>
 */
@Slf4j
public final class LearnedGuidance {

    private final LessonBundle bundle;
    private final Supplier<Optional<String>> revocationCheck;
    private volatile String revokedReason;

    /**
     * @param bundle          the guidance supplied; must carry lessons
     * @param revocationCheck why the guidance no longer holds, or empty while it does
     */
    public LearnedGuidance(LessonBundle bundle, Supplier<Optional<String>> revocationCheck) {
        if (bundle == null || !bundle.hasGuidance()) {
            throw new IllegalArgumentException("Learned guidance needs at least one lesson");
        }
        this.bundle = bundle;
        this.revocationCheck = revocationCheck;
    }

    public LessonBundle bundle() {
        return bundle;
    }

    /** The guidance block exactly as the model received it. */
    public String text() {
        return bundle.text();
    }

    /** Asks again whether the guidance still holds; a revocation, once seen, is final. */
    public Optional<String> recheck() {
        if (revokedReason != null) {
            return Optional.of(revokedReason);
        }
        if (revocationCheck == null) {
            return Optional.empty();
        }
        Optional<String> reason;
        try {
            reason = revocationCheck.get();
        } catch (RuntimeException e) {
            log.warn("Could not recheck learned guidance {}: {} — continuing with it",
                    bundle.revisionReferences(), e.getMessage());
            return Optional.empty();
        }
        if (reason != null && reason.isPresent()) {
            revokedReason = reason.get();
            log.warn("Learned guidance {} was revoked during the run: {}",
                    bundle.revisionReferences(), revokedReason);
            return reason;
        }
        return Optional.empty();
    }

    /** The revocation already observed, without checking again. */
    public Optional<String> revokedReason() {
        return Optional.ofNullable(revokedReason);
    }
}
