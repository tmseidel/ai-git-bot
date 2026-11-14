package org.remus.giteabot.prworkflow.agentreview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.structured.FinalizedReview;
import org.remus.giteabot.review.structured.ReviewCommentRenderer;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewLinkStyle;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes one finalized agentic review: loads the stored record, renders it, posts it with the
 * formal decision the server computes from the persisted findings, and emits the finding events.
 *
 * <p>Publication is a pure function of the stored review. Nothing here re-reads model text, so a
 * renderer change, a retry after a failed delivery or an outgoing webhook all describe the same
 * review. A formal-review submission that fails is retried as a plain comment; that never
 * changes the structured source.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewPublisher {

    private final StructuredReviewService reviewService;
    private final ReviewCommentRenderer renderer;
    private final EventHookPublisher eventHookPublisher;

    /**
     * @return {@code true} when the review reached the pull request
     * @throws RuntimeException when neither the formal submission nor the plain fallback worked
     */
    public boolean publish(RepositoryApiClient client, Bot bot, String owner, String repo,
                           Long prNumber, String outputReference, boolean enableFormalDecision,
                           AgentReviewService.SeverityThresholds thresholds,
                           ReviewLinkStyle linkStyle) {
        FinalizedReview review = reviewService.findFinalizedByReference(outputReference).orElse(null);
        if (review == null) {
            // Never publish what could not be persistently finalized: the comment would be the only
            // witness of a review the application cannot reproduce.
            log.error("Finalized agentic review {} is missing; nothing will be published", outputReference);
            return false;
        }

        String body = renderer.render(review, linkStyle);
        PostReviewAction action = enableFormalDecision
                ? decision(review, thresholds) : PostReviewAction.NONE;
        try {
            client.postReview(owner, repo, prNumber, body, action);
            if (action != PostReviewAction.NONE) {
                log.info("Agentic review {} posted formal decision {} for PR #{} in {}/{}",
                        review.reference(), action, prNumber, owner, repo);
            }
        } catch (RuntimeException e) {
            if (action == PostReviewAction.NONE) {
                throw e;
            }
            // The findings survive a failed formal submission; the stored review is unchanged.
            log.warn("Failed to post formal review {} for {} on PR #{} in {}/{}: {} — "
                            + "falling back to a plain review comment",
                    action, review.reference(), prNumber, owner, repo, e.getMessage());
            client.postReviewComment(owner, repo, prNumber, body);
        }

        publishFindingEvents(bot, review, owner, repo, prNumber);
        log.info("Agentic review {} published for PR #{} in {}/{} ({} finding(s))",
                review.reference(), prNumber, owner, repo, review.findings().size());
        return true;
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
