package org.remus.giteabot.reviewquality;

import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The stored facts one Review quality page is computed from, read by {@link ReviewQualityLoader} in
 * a fixed number of queries and aggregated by {@link ReviewQualityCalculator}.
 */
public final class ReviewQualityFacts {

    private ReviewQualityFacts() {
    }

    /** A pull request as the page counts it: per Git integration, bot, repository and number. */
    public record PrKey(Long integrationId, long botId, String owner, String repo, long number) {

        public String project() {
            return owner + "/" + repo;
        }
    }

    /** One agentic review (any state). Owner and repository are lower case. */
    public record ReviewFact(long reviewId, long outputId, String reference, long botId, Long integrationId,
                             String owner, String repo, long prNumber, String author, String title, Long runId,
                             ReviewState state, Instant createdAt, ReviewConclusion conclusion,
                             Integer diffFiles, Integer diffAdditions, Integer diffDeletions,
                             boolean fingerprinted) {

        public PrKey pr() {
            return new PrKey(integrationId, botId, owner, repo, prNumber);
        }

        public boolean finalized() {
            return state == ReviewState.FINALIZED;
        }

        /** Changed lines, or {@code null} when the diff size was not recorded. */
        public Integer changedLines() {
            return diffAdditions == null || diffDeletions == null ? null : diffAdditions + diffDeletions;
        }
    }

    /** One finding that was not withdrawn; the category is normalised. */
    public record FindingFact(long id, long reviewId, ReviewSeverity severity, String category,
                              Long originId, String path) {

        /** The chain this finding belongs to: its first finding, or itself when it was not matched. */
        public long chain() {
            return originId != null ? originId : id;
        }
    }

    /** One effective feedback record on a review in scope; {@code findingId} only for finding feedback. */
    public record FeedbackFact(long outputId, Long findingId, FeedbackVerdict verdict, Instant createdAt) {
    }

    /** The recorded outcome of a pull request. */
    public record OutcomeFact(PrOutcome.State state, Instant closedAt) {
    }

    /** Token usage of one workflow run with one AI integration. */
    public record RunUsage(long runId, Long botId, String owner, String repo, Long prNumber, Instant startedAt,
                           String integration, long inputTokens, long outputTokens, long cacheWriteTokens,
                           long cacheReadTokens) {
    }

    /** Since when each new kind of data is recorded at all ({@code null}: not yet). */
    public record DataSince(Instant fingerprints, Instant authors, Instant diffSize, Instant runLinks,
                            Instant outcomes) {
    }

    /** Everything one page is computed from. */
    public record Dataset(List<ReviewFact> reviews, Map<PrKey, Instant> firstFinalized, List<FindingFact> findings,
                          List<FeedbackFact> feedback, Map<PrKey, OutcomeFact> outcomes, List<RunUsage> runs,
                          Map<Long, String> botNames, DataSince since) {
    }
}
