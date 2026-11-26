package org.remus.giteabot.reviewui;

import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackTargetType;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LessonState;
import org.remus.giteabot.review.structured.FinalizedReview;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewPublicationState;
import org.remus.giteabot.review.structured.ReviewState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the Reviews pages render; built by {@link ReviewUiQueryService}. */
public final class ReviewUiViews {

    private ReviewUiViews() {
    }

    /** A bot offered by the filter. */
    public record BotOption(Long id, String name) {
    }

    /** One page of rows. {@code page} is 1-based. */
    public record PageOf<T>(List<T> rows, int page, int pageSize, long total) {

        public int pages() {
            return (int) Math.max(1, (total + pageSize - 1) / pageSize);
        }

        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < pages();
        }
    }

    /** One agentic review in the list. */
    public record ReviewRow(Long reviewId, Long outputId, String reference, Long botId, String botName,
                            String repoOwner, String repoName, Long prNumber, ReviewState state,
                            String headSha, Instant createdAt, Instant finalizedAt,
                            ReviewConclusion conclusion, int blockers, int medium, int low,
                            long feedback, List<String> lessons,
                            ReviewPublicationState publication) {

        public String project() {
            return repoOwner + "/" + repoName;
        }

        public String shortSha() {
            return headSha == null ? null : headSha.substring(0, Math.min(7, headSha.length()));
        }
    }

    /** One feedback record on a review target. */
    public record FeedbackItem(String reference, String targetReference, FeedbackTargetType targetType,
                               FeedbackVerdict verdict, FeedbackState state, String reason,
                               String actor, String supersededBy, Instant createdAt) {
    }

    /** The review page. {@code finalized} is empty for reviews that never finalized. */
    public record ReviewDetail(ReviewRow row, FinalizedReview finalized,
                               Map<String, List<FeedbackItem>> feedbackByTarget,
                               List<ReviewRow> pullRequestReviews, String workflowRunsQuery) {

        public List<FeedbackItem> feedbackOn(String target) {
            return feedbackByTarget.getOrDefault(target, List.of());
        }
    }

    /** A lesson revision citing a feedback record, as {@code L17@1 (active)}. */
    public record CitingLesson(String lessonReference, int revision, LessonState state) {

        public String label() {
            return lessonReference + "@" + revision + " (" + state.label() + ")";
        }
    }

    /** One feedback record in the list. */
    public record FeedbackRow(FeedbackItem item, Long botId, String botName, String repoOwner,
                              String repoName, Long prNumber, List<CitingLesson> lessons,
                              boolean curated) {

        public String project() {
            return repoOwner + "/" + repoName;
        }

        /** {@code O…} of the review the target belongs to. */
        public String outputReference() {
            String target = item.targetReference();
            int dash = target.indexOf('-');
            return dash < 0 ? target : target.substring(0, dash);
        }
    }

    /** One lesson in the list. */
    public record LessonRow(KnowledgeLessonService.Summary summary, Long botId, String botName,
                            boolean curated) {

        public String project() {
            return summary.repoOwner() + "/" + summary.repoName();
        }

        public boolean needsAttention() {
            return ReviewUiQueryService.needsAttention(summary);
        }
    }

    /** One review a lesson revision was supplied to. */
    public record LessonUse(String outputReference, String repoOwner, String repoName, Long prNumber,
                            int revision, Instant suppliedAt) {
    }

    /**
     * The lesson page: {@code learning show}, the bot, recent usage, and the text a revise form starts
     * from.
     */
    public record LessonDetail(KnowledgeLessonService.Detail detail, Long botId, String botName,
                               boolean curated, String learningMode, List<LessonUse> usage,
                               KnowledgeLessonService.RevisionView latest, String revisePaths) {

        /** Only the latest revision can be activated, and only while it is a candidate or disabled. */
        public boolean canActivate() {
            return latest != null
                    && (latest.state() == LessonState.CANDIDATE || latest.state() == LessonState.DISABLED);
        }

        public boolean canReject() {
            return latest != null && latest.state() == LessonState.CANDIDATE;
        }

        public boolean canDisable() {
            return detail.summary().activeRevision() != null;
        }

        /** The waiting candidate when it differs from the active revision, else {@code null}. */
        public KnowledgeLessonService.RevisionView pendingCandidate() {
            return latest != null && latest.state() == LessonState.CANDIDATE
                    && detail.summary().activeRevision() != null ? latest : null;
        }
    }

    /** A lesson listing; {@code truncated} when more lessons matched than one listing reads. */
    public record LessonPage(PageOf<LessonRow> page, boolean truncated, int scanLimit) {
    }
}
