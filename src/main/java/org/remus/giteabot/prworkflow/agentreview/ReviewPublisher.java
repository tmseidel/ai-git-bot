package org.remus.giteabot.prworkflow.agentreview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.repository.DeliveryFailures;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.structured.FinalizedReview;
import org.remus.giteabot.review.structured.ReviewCommentRenderer;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewLinkStyle;
import org.remus.giteabot.review.structured.ReviewPublication;
import org.remus.giteabot.review.structured.ReviewPublicationService;
import org.remus.giteabot.review.structured.ReviewPublicationState;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Publishes one finalized agentic review: loads the stored record, renders it, records the delivery
 * intent, posts it with the formal decision the server computes from the persisted findings, and
 * reports the outcome.
 *
 * <p>Publication is a pure function of the stored review, so a renderer change, a retry after a
 * failed delivery or an outgoing webhook all describe the same review — republishing never calls
 * the model. The intent is committed before the Git call: a crash leaves a visible {@code PENDING}
 * row rather than a silently missing review, and an unknown outcome is recorded as
 * {@code UNCERTAIN} instead of being retried into a duplicate.</p>
 *
 * <p>No path posts a review whose earlier delivery is {@code PUBLISHED}, {@code PENDING} or
 * {@code UNCERTAIN}: the first publication and a republish alike stop with
 * {@link PublicationOutcome#ALREADY_PUBLISHED} or {@link PublicationOutcome#UNRESOLVED}. Only
 * {@link #republishConfirmedNotDelivered} — a person checked the pull request — overrides an
 * unresolved delivery.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewPublisher {

    private final StructuredReviewService reviewService;
    private final ReviewPublicationService publicationService;
    private final ReviewCommentRenderer renderer;
    private final EventHookPublisher eventHookPublisher;

    /**
     * Everything one delivery needs. {@code expectedHeadSha} pins the revision the review describes;
     * a moved head suppresses the publication instead of posting a stale review.
     */
    public record PublicationRequest(
            RepositoryApiClient client,
            Bot bot,
            String owner,
            String repo,
            Long prNumber,
            String outputReference,
            boolean enableFormalDecision,
            AgentReviewService.SeverityThresholds thresholds,
            ReviewLinkStyle linkStyle,
            String provider,
            String channel,
            String expectedHeadSha,
            Supplier<String> currentHeadSha,
            BooleanSupplier cancelled,
            String feedbackHint) {
    }

    /** What a delivery attempt did. Nothing posted is not the same as something failed. */
    public enum PublicationOutcome {
        PUBLISHED,
        /** Deliberately not posted: the review no longer describes the pull request. */
        SUPPRESSED,
        /** Already on the pull request; a second copy would duplicate it. */
        ALREADY_PUBLISHED,
        /**
         * An earlier delivery is {@code PENDING} or {@code UNCERTAIN} and may already be on the pull
         * request. Nothing is posted until a person confirms it is not.
         */
        UNRESOLVED
    }

    /**
     * @param reason why nothing was posted, for the run log; {@code null} when it was published
     */
    public record Publication(PublicationOutcome outcome, String reason) {

        static Publication published() {
            return new Publication(PublicationOutcome.PUBLISHED, null);
        }

        static Publication suppressed(String reason) {
            return new Publication(PublicationOutcome.SUPPRESSED, reason);
        }

        public boolean isPublished() {
            return outcome == PublicationOutcome.PUBLISHED;
        }
    }

    /** Publishes a finalized review that has no earlier delivery that may have arrived. */
    public Publication publish(PublicationRequest request) {
        return deliver(request, false);
    }

    /**
     * Retries a delivery that definitely failed, by rerendering the same stored review. A published
     * review is not reposted — updating a bot-owned comment is a separate, deliberate action — and a
     * {@code PENDING} or {@code UNCERTAIN} delivery is answered with
     * {@link PublicationOutcome#UNRESOLVED}: whether it arrived is for a person to check.
     */
    public Publication republish(PublicationRequest request) {
        return deliver(request, false);
    }

    /**
     * Retries after a person checked the pull request and found no copy of an earlier
     * {@code PENDING} or {@code UNCERTAIN} delivery; those deliveries are recorded as
     * {@code FAILED}. A {@code PUBLISHED} delivery still blocks.
     */
    public Publication republishConfirmedNotDelivered(PublicationRequest request) {
        return deliver(request, true);
    }

    private Publication deliver(PublicationRequest request, boolean confirmedNotDelivered) {
        FinalizedReview review = reviewService.findFinalizedByReference(request.outputReference())
                .orElse(null);
        if (review == null) {
            // Never publish what could not be persistently finalized: the comment would be the only
            // witness of a review the application cannot reproduce.
            log.error("Finalized agentic review {} is missing; nothing will be published",
                    request.outputReference());
            return Publication.suppressed("the finalized review record is missing");
        }
        String body = request.feedbackHint() == null
                ? renderer.render(review, request.linkStyle())
                : renderer.render(review, request.linkStyle()) + "\n\n" + request.feedbackHint();
        ReviewPublicationService.Start start = publicationService.start(review.reference(),
                request.provider(), request.channel(), body, confirmedNotDelivered);
        if (!start.isStarted()) {
            return refused(review, start.blockedBy());
        }
        ReviewPublication publication = start.publication();

        String suppression = suppressionReason(request);
        if (suppression != null) {
            publicationService.finish(publication.getId(), ReviewPublicationState.SUPPRESSED, null);
            log.info("Agentic review {} suppressed: {}", review.reference(), suppression);
            return Publication.suppressed(suppression);
        }

        PostReviewAction action = request.enableFormalDecision()
                ? decision(review, request.thresholds()) : PostReviewAction.NONE;
        try {
            request.client().postReview(request.owner(), request.repo(), request.prNumber(), body, action);
            publicationService.finish(publication.getId(), ReviewPublicationState.PUBLISHED, null);
            if (action != PostReviewAction.NONE) {
                log.info("Agentic review {} posted formal decision {} for PR #{} in {}/{}",
                        review.reference(), action, request.prNumber(), request.owner(), request.repo());
            }
        } catch (RuntimeException e) {
            ReviewPublicationState state = classify(e);
            // An uncertain outcome means the provider may already have taken the review. Posting the
            // fallback comment then puts the same findings on the pull request twice, which is the
            // duplicate this class exists to prevent — so only a definite refusal falls back.
            if (action == PostReviewAction.NONE || state == ReviewPublicationState.UNCERTAIN) {
                publicationService.finish(publication.getId(), state, null);
                if (state == ReviewPublicationState.UNCERTAIN) {
                    log.warn("Delivery of agentic review {} is uncertain ({}); no fallback is attempted. "
                                    + "Check the pull request: only a confirmed republish posts it again",
                            review.reference(), e.getMessage());
                }
                throw e;
            }
            // The findings survive a refused formal submission; the stored review is unchanged.
            log.warn("Failed to post formal review {} for {} on PR #{} in {}/{}: {} — "
                            + "falling back to a plain review comment",
                    action, review.reference(), request.prNumber(), request.owner(), request.repo(),
                    e.getMessage());
            try {
                request.client().postReviewComment(request.owner(), request.repo(), request.prNumber(),
                        body);
                publicationService.finish(publication.getId(), ReviewPublicationState.PUBLISHED, null);
            } catch (RuntimeException fallback) {
                publicationService.finish(publication.getId(), classify(fallback), null);
                throw fallback;
            }
        }

        publishFindingEvents(request.bot(), review, request.owner(), request.repo(), request.prNumber());
        log.info("Agentic review {} published for PR #{} in {}/{} ({} finding(s))",
                review.reference(), request.prNumber(), request.owner(), request.repo(),
                review.findings().size());
        return Publication.published();
    }

    private static Publication refused(FinalizedReview review, ReviewPublicationState blockedBy) {
        if (blockedBy == ReviewPublicationState.PUBLISHED) {
            log.info("Agentic review {} is already published; refusing to post a duplicate. "
                    + "Update the existing comment instead.", review.reference());
            return new Publication(PublicationOutcome.ALREADY_PUBLISHED,
                    "the review is already published on this pull request");
        }
        log.warn("Agentic review {} has a {} delivery that may already be on the pull request; "
                + "not posting again until a person confirms it is not", review.reference(), blockedBy);
        return new Publication(PublicationOutcome.UNRESOLVED, "an earlier delivery is "
                + blockedBy.name().toLowerCase(Locale.ROOT)
                + " and may already be on the pull request; check it before republishing");
    }

    /**
     * Why this publication must not happen, or {@code null} when it may. A cancelled run and a
     * moved head both mean the review no longer describes the pull request it would be posted on:
     * the record stays stored, the delivery is suppressed.
     */
    private static String suppressionReason(PublicationRequest request) {
        if (request.cancelled() != null && request.cancelled().getAsBoolean()) {
            return "the run was cancelled before publication";
        }
        if (request.expectedHeadSha() == null || request.expectedHeadSha().isBlank()
                || request.currentHeadSha() == null) {
            return null;
        }
        String current = request.currentHeadSha().get();
        if (current != null && !current.isBlank() && !current.equals(request.expectedHeadSha())) {
            return "the pull request head moved from " + request.expectedHeadSha() + " to " + current;
        }
        return null;
    }

    /**
     * A definite refusal is retryable as {@code FAILED}; an outcome the provider may still have
     * applied is {@code UNCERTAIN}, since claiming {@code FAILED} there would invite a duplicate
     * comment. See {@link DeliveryFailures}.
     */
    static ReviewPublicationState classify(RuntimeException failure) {
        return DeliveryFailures.isDefiniteRefusal(failure)
                ? ReviewPublicationState.FAILED : ReviewPublicationState.UNCERTAIN;
    }

    /**
     * Formal decision from the persisted severity totals and the configured thresholds. An
     * inconclusive review never approves, and an AI cannot supply a decision of its own.
     */
    static PostReviewAction decision(FinalizedReview review,
                                     AgentReviewService.SeverityThresholds thresholds) {
        if (review.conclusion() == ReviewConclusion.INCONCLUSIVE || thresholds == null) {
            return PostReviewAction.NONE;
        }
        boolean anyConfigured = thresholds.blocker() != null
                || thresholds.medium() != null
                || thresholds.low() != null;
        if (!anyConfigured) {
            return PostReviewAction.NONE;
        }
        FinalizedReview.SeverityCounts counts = review.counts();
        if (thresholds.blocker() != null && counts.blocker() > thresholds.blocker()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        if (thresholds.medium() != null && counts.medium() > thresholds.medium()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        if (thresholds.low() != null && counts.low() > thresholds.low()) {
            return PostReviewAction.REQUEST_CHANGES;
        }
        return PostReviewAction.APPROVE;
    }

    /**
     * One event per persisted finding, carrying its stable reference. The documented fields are
     * preserved ({@code severity}, {@code category}, {@code title}, {@code file}, {@code line}) and
     * the {@code reference} is added next to them.
     */
    private void publishFindingEvents(Bot bot, FinalizedReview review, String owner, String repo,
                                      Long prNumber) {
        for (FinalizedReview.Finding finding : review.findings()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reference", finding.reference());
            payload.put("severity", finding.severity().name().toLowerCase(Locale.ROOT));
            payload.put("category", finding.category());
            payload.put("title", finding.title());
            if (!finding.locations().isEmpty()) {
                FinalizedReview.Location first = finding.locations().getFirst();
                payload.put("file", first.path());
                payload.put("side", first.side().name());
                if (first.startLine() != null) {
                    payload.put("line", first.startLine());
                }
                if (first.endLine() != null) {
                    payload.put("endLine", first.endLine());
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("review", review.reference());
            data.put("finding", payload);
            eventHookPublisher.publish(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED, bot, owner,
                    repo, prNumber, null, data);
        }
    }
}
