package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owns the agentic-review draft: creation, finding/assessment submission, and the single
 * finalizing transition that freezes the record.
 *
 * <p>Every mutation takes the caller's expected {@code draftRevision}; a mismatch, a
 * non-draft record or a mismatched finding count is reported as
 * {@link ReviewDraftConflictException} carrying the current revision, so a tool layer can
 * tell the model to re-read the draft instead of guessing. Content is validated here, not
 * in the caller: severity counts, ordinals, references and the content hash are always
 * derived from persisted rows.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredReviewService {

    public static final String WORKFLOW_KEY = "agentic-review";
    public static final String STAGE_REVIEW = "review";
    public static final int SCHEMA_VERSION = 1;

    static final int MAX_FINDINGS = 50;
    static final int MAX_LOCATIONS_PER_FINDING = 20;
    static final int MAX_CLIENT_KEY_CHARS = 128;
    static final int MAX_CATEGORY_CHARS = 64;
    static final int MAX_TITLE_CHARS = 512;
    static final int MAX_EXPLANATION_CHARS = 20_000;
    static final int MAX_IMPACT_CHARS = 4_000;
    static final int MAX_RECOMMENDATION_CHARS = 4_000;
    static final int MAX_SUMMARY_CHARS = 4_000;
    static final int MAX_REVIEWED_AREAS_CHARS = 4_000;
    static final int MAX_LIMITATIONS_CHARS = 4_000;
    static final int MAX_LOCATION_DESCRIPTION_CHARS = 4_000;
    static final int MAX_PATH_CHARS = 1024;
    static final int MAX_WITHDRAWAL_REASON_CHARS = 1024;

    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;
    private final ReferenceAllocator referenceAllocator;

    /**
     * Creates the {@code O<n>} output identity and its empty draft in one transaction. The
     * reference is allocated before the row is written, so a caller never holds a reference
     * that was not reserved.
     */
    @Transactional
    public ReviewDraftHandle createDraft(ReviewDraftRequest request) {
        WorkflowOutput output = new WorkflowOutput();
        output.setReference(referenceAllocator.next(ReferenceNamespace.OUTPUT));
        output.setGitIntegrationId(requireValue(request.gitIntegrationId(), "gitIntegrationId"));
        output.setBotId(requireValue(request.botId(), "botId"));
        output.setRepoOwner(requireText(request.repoOwner(), 255, "repoOwner"));
        output.setRepoName(requireText(request.repoName(), 255, "repoName"));
        output.setProviderRepositoryId(optionalText(request.providerRepositoryId(), 255,
                "providerRepositoryId"));
        output.setPrNumber(requireValue(request.prNumber(), "prNumber"));
        output.setWorkflowKey(WORKFLOW_KEY);
        output.setStage(STAGE_REVIEW);
        output.setPrWorkflowRunId(request.prWorkflowRunId());
        output.setInvocationId(requireText(request.invocationId(), 36, "invocationId"));
        outputRepository.save(output);

        ReviewRecord record = new ReviewRecord();
        record.setOutput(output);
        record.setState(ReviewState.DRAFT);
        record.setSchemaVersion(SCHEMA_VERSION);
        record.setBaseSha(optionalText(request.baseSha(), 64, "baseSha"));
        record.setHeadSha(optionalText(request.headSha(), 64, "headSha"));
        record.setDraftRevision(0);
        reviewRepository.save(record);

        log.debug("Created review draft {} ({}) for {} {}/{}#{}", record.getId(),
                output.getReference(), output.getWorkflowKey(), output.getRepoOwner(),
                output.getRepoName(), output.getPrNumber());
        return new ReviewDraftHandle(record.getId(), output.getReference(), record.getDraftRevision());
    }

    /**
     * Creates or updates the finding identified by {@code draft.clientKey()}. A correction
     * keeps the finding's ordinal and reference and advances its revision; any change
     * invalidates the current assessment.
     *
     * <p>No draft revision is required here: the finding's own {@code expectedFindingRevision}
     * is the conflict guard, so several findings can be submitted in one batch without each
     * submission invalidating the next.</p>
     */
    @Transactional
    public FindingHandle upsertFinding(long reviewId, Integer expectedFindingRevision,
                                       FindingDraft draft) {
        ReviewRecord review = requireMutableDraft(reviewId);
        if (draft == null) {
            throw new InvalidReviewContentException("finding", "A finding submission is required");
        }
        String clientKey = requireText(draft.clientKey(), MAX_CLIENT_KEY_CHARS, "clientKey");
        List<LocationDraft> locationDrafts = draft.locations() == null ? List.of() : draft.locations();
        if (locationDrafts.size() > MAX_LOCATIONS_PER_FINDING) {
            throw new InvalidReviewContentException("locations",
                    "At most " + MAX_LOCATIONS_PER_FINDING + " locations are allowed per finding");
        }
        validateLocations(locationDrafts);

        ReviewFinding finding = findActiveByClientKey(review, clientKey);
        if (finding != null && expectedFindingRevision != null
                && expectedFindingRevision != finding.getRevision()) {
            throw new ReviewDraftConflictException("Finding '" + clientKey + "' is at revision "
                    + finding.getRevision() + ", not " + expectedFindingRevision,
                    review.getDraftRevision());
        }
        if (finding == null) {
            if (findByClientKey(review, clientKey) != null) {
                throw new ReviewDraftConflictException("Finding '" + clientKey
                        + "' was withdrawn; submit a new clientKey", review.getDraftRevision());
            }
            if (review.getFindings().size() >= MAX_FINDINGS) {
                throw new InvalidReviewContentException("findings",
                        "At most " + MAX_FINDINGS + " findings are allowed per review");
            }
            finding = new ReviewFinding();
            finding.setReview(review);
            finding.setClientKey(clientKey);
            finding.setOrdinal(nextOrdinal(review));
            review.getFindings().add(finding);
        }

        finding.setSeverity(requireSeverity(draft.severity()));
        finding.setCategory(requireText(draft.category(), MAX_CATEGORY_CHARS, "category"));
        finding.setTitle(requireText(draft.title(), MAX_TITLE_CHARS, "title"));
        finding.setExplanation(requireText(draft.explanation(), MAX_EXPLANATION_CHARS, "explanation"));
        finding.setImpact(requireText(draft.impact(), MAX_IMPACT_CHARS, "impact"));
        finding.setRecommendation(
                requireText(draft.recommendation(), MAX_RECOMMENDATION_CHARS, "recommendation"));
        finding.setRevision(finding.getRevision() + 1);
        replaceLocations(finding, locationDrafts);

        invalidateAssessment(review);
        review.bumpDraftRevision();
        return new FindingHandle(finding.getOrdinal(),
                ReviewReferences.finding(review.getOutput().getReference(), finding.getOrdinal()),
                finding.getRevision());
    }

    /** Withdraws a finding without deleting it: the ordinal is never reused. */
    @Transactional
    public ReviewDraftHandle withdrawFinding(long reviewId, int ordinal, int findingRevision,
                                             String reason) {
        ReviewRecord review = requireMutableDraft(reviewId);
        ReviewFinding finding = findByOrdinal(review, ordinal);
        if (finding.isWithdrawn()) {
            throw new ReviewDraftConflictException("Finding " + ordinal + " is already withdrawn",
                    review.getDraftRevision());
        }
        if (finding.getRevision() != findingRevision) {
            throw new ReviewDraftConflictException("Finding " + ordinal + " is at revision "
                    + finding.getRevision() + ", not " + findingRevision,
                    review.getDraftRevision());
        }
        finding.setWithdrawn(true);
        finding.setWithdrawalReason(requireText(reason, MAX_WITHDRAWAL_REASON_CHARS, "reason"));
        finding.setRevision(finding.getRevision() + 1);

        invalidateAssessment(review);
        review.bumpDraftRevision();
        return new ReviewDraftHandle(review.getId(), review.getOutput().getReference(),
                review.getDraftRevision());
    }

    /**
     * Sets the overall assessment and binds it to the current finding-set revision. The
     * conclusion must agree with the active findings; an inconclusive review must state
     * what it did not verify.
     */
    @Transactional
    public ReviewDraftHandle setAssessment(long reviewId, int expectedDraftRevision,
                                           AssessmentDraft draft) {
        ReviewRecord review = requireDraftAtRevision(reviewId, expectedDraftRevision);
        if (draft == null || draft.conclusion() == null) {
            throw new InvalidReviewContentException("conclusion", "A conclusion is required");
        }
        int activeFindings = activeFindings(review).size();
        switch (draft.conclusion()) {
            case FINDINGS_PRESENT -> {
                if (activeFindings == 0) {
                    throw new InvalidReviewContentException("conclusion",
                            "FINDINGS_PRESENT requires at least one active finding");
                }
            }
            case NO_FINDINGS -> {
                if (activeFindings > 0) {
                    throw new InvalidReviewContentException("conclusion",
                            "NO_FINDINGS contradicts the " + activeFindings + " active finding(s)");
                }
            }
            case INCONCLUSIVE -> {
                // an inconclusive review must say what it could not verify
            }
        }
        String limitations = optionalText(draft.limitations(), MAX_LIMITATIONS_CHARS, "limitations");
        if (draft.conclusion() == ReviewConclusion.INCONCLUSIVE && limitations == null) {
            throw new InvalidReviewContentException("limitations",
                    "An inconclusive review must state its limitations");
        }

        ReviewAssessment assessment = review.getAssessment();
        if (assessment == null) {
            assessment = new ReviewAssessment();
            assessment.setReview(review);
            review.setAssessment(assessment);
        }
        assessment.setSummary(requireText(draft.summary(), MAX_SUMMARY_CHARS, "summary"));
        assessment.setConclusion(draft.conclusion());
        assessment.setReviewedAreas(optionalText(draft.reviewedAreas(), MAX_REVIEWED_AREAS_CHARS,
                "reviewedAreas"));
        assessment.setLimitations(limitations);
        assessment.setAssessedRevision(review.getDraftRevision() + 1);

        review.bumpDraftRevision();
        return new ReviewDraftHandle(review.getId(), review.getOutput().getReference(),
                review.getDraftRevision());
    }

    /**
     * Freezes the draft in one transaction: revision, finding count, completeness and
     * assessment freshness are re-checked, then severity totals and the content hash are
     * computed from the persisted rows. Nothing can change a finalized review afterwards.
     */
    @Transactional
    public FinalizedReview finalizeReview(long reviewId, int expectedDraftRevision,
                                          int expectedFindingCount) {
        ReviewRecord review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + reviewId));
        if (!review.isMutable()) {
            throw new ReviewDraftConflictException("Review " + reviewId + " is already "
                    + review.getState(), review.getDraftRevision());
        }
        if (review.getDraftRevision() != expectedDraftRevision) {
            throw new ReviewDraftConflictException("Draft revision is " + review.getDraftRevision()
                    + ", not " + expectedDraftRevision, review.getDraftRevision());
        }
        List<ReviewFinding> active = activeFindings(review);
        if (active.size() != expectedFindingCount) {
            throw new ReviewDraftConflictException("Draft has " + active.size()
                    + " active finding(s), not " + expectedFindingCount,
                    review.getDraftRevision());
        }
        ReviewAssessment assessment = review.getAssessment();
        if (assessment == null) {
            throw new InvalidReviewContentException("assessment",
                    "An assessment is required before completing the review");
        }
        if (assessment.getAssessedRevision() != review.getDraftRevision()) {
            throw new InvalidReviewContentException("assessment",
                    "The assessment does not cover the current finding set; set it again");
        }
        if (assessment.getConclusion() == ReviewConclusion.INCONCLUSIVE
                && assessment.getLimitations() == null) {
            throw new InvalidReviewContentException("limitations",
                    "An inconclusive review must state its limitations");
        }
        if (assessment.getConclusion() == ReviewConclusion.NO_FINDINGS && !active.isEmpty()) {
            throw new InvalidReviewContentException("conclusion",
                    "NO_FINDINGS contradicts the " + active.size() + " active finding(s)");
        }
        if (assessment.getConclusion() == ReviewConclusion.FINDINGS_PRESENT && active.isEmpty()) {
            throw new InvalidReviewContentException("conclusion",
                    "FINDINGS_PRESENT requires at least one active finding");
        }

        review.setState(ReviewState.FINALIZED);
        review.setFinalizedAt(Instant.now());
        FinalizedReview finalized = toFinalized(review);
        review.setContentHash(hash(finalized));
        log.debug("Finalized review {} with {} finding(s)", finalized.reference(),
                finalized.findings().size());
        return toFinalized(review);
    }

    /**
     * Closes a draft that will never be finalized, so a later attempt cannot append to it.
     * A finalized review is never rewritten; only a draft can move to FAILED, CANCELLED or
     * ABANDONED, and any other target is a programming error.
     */
    @Transactional
    public void closeDraft(long reviewId, ReviewState target) {
        if (target == ReviewState.DRAFT || target == ReviewState.FINALIZED) {
            throw new InvalidReviewContentException("state",
                    "A draft can only be closed as FAILED, CANCELLED or ABANDONED");
        }
        reviewRepository.findById(reviewId).filter(ReviewRecord::isMutable).ifPresent(review -> {
            review.setState(target);
            log.info("Review draft {} closed as {}", reviewId, target);
        });
    }

    @Transactional(readOnly = true)
    public Optional<FinalizedReview> findFinalized(long reviewId) {
        return reviewRepository.findById(reviewId)
                .filter(record -> record.getState() == ReviewState.FINALIZED)
                .map(StructuredReviewService::toFinalized);
    }

    @Transactional(readOnly = true)
    public Optional<FinalizedReview> findFinalizedByReference(String outputReference) {
        if (outputReference == null || outputReference.isBlank()) {
            return Optional.empty();
        }
        return outputRepository.findByReference(outputReference.trim())
                .flatMap(output -> reviewRepository.findByOutputId(output.getId()))
                .filter(record -> record.getState() == ReviewState.FINALIZED)
                .map(StructuredReviewService::toFinalized);
    }

    /** Draft snapshot for a later re-read, e.g. after a conflict or a context compaction. */
    @Transactional(readOnly = true)
    public Optional<DraftSnapshot> findDraft(long reviewId) {
        return reviewRepository.findById(reviewId).map(StructuredReviewService::toSnapshot);
    }

    /**
     * Persisted scope of one review. The review-tool layer compares it with its trusted
     * invocation before applying anything, so a tool call cannot reach another bot's,
     * repository's or pull request's review.
     */
    @Transactional(readOnly = true)
    public Optional<ReviewScope> findScope(long reviewId) {
        return reviewRepository.findById(reviewId).map(review -> {
            WorkflowOutput output = review.getOutput();
            return new ReviewScope(review.getId(), output.getReference(), output.getBotId(),
                    output.getGitIntegrationId(), output.getRepoOwner(), output.getRepoName(),
                    output.getPrNumber(), output.getPrWorkflowRunId(), output.getInvocationId(),
                    review.getState());
        });
    }

    private static DraftSnapshot toSnapshot(ReviewRecord review) {
        String reference = review.getOutput().getReference();
        List<DraftSnapshot.FindingSummary> findings = activeFindings(review).stream()
                .map(finding -> new DraftSnapshot.FindingSummary(
                        ReviewReferences.finding(reference, finding.getOrdinal()),
                        finding.getOrdinal(), finding.getClientKey(), finding.getRevision(),
                        finding.getSeverity(), finding.getTitle()))
                .toList();
        ReviewAssessment assessment = review.getAssessment();
        boolean assessed = assessment != null
                && assessment.getAssessedRevision() == review.getDraftRevision();
        return new DraftSnapshot(review.getId(), reference, review.getState(),
                review.getDraftRevision(), findings,
                assessment == null ? null : assessment.getConclusion(),
                assessment == null ? null : assessment.getSummary(), assessed);
    }

    private static FinalizedReview toFinalized(ReviewRecord review) {
        String reference = review.getOutput().getReference();
        List<FinalizedReview.Finding> findings = activeFindings(review).stream()
                .map(finding -> new FinalizedReview.Finding(
                        ReviewReferences.finding(reference, finding.getOrdinal()),
                        finding.getSeverity(), finding.getCategory(), finding.getTitle(),
                        finding.getExplanation(), finding.getImpact(), finding.getRecommendation(),
                        finding.getLocations().stream()
                                .map(location -> new FinalizedReview.Location(location.getPath(),
                                        location.getSide(), location.getStartLine(),
                                        location.getEndLine(), location.getDescription()))
                                .toList()))
                .toList();
        ReviewAssessment assessment = review.getAssessment();
        return new FinalizedReview(reference, ReviewReferences.assessment(reference),
                assessment == null ? ReviewConclusion.INCONCLUSIVE : assessment.getConclusion(),
                assessment == null ? "" : assessment.getSummary(),
                assessment == null ? null : assessment.getReviewedAreas(),
                assessment == null ? null : assessment.getLimitations(),
                review.getServerCoverageNote(),
                FinalizedReview.SeverityCounts.of(findings),
                findings,
                review.getContentHash());
    }

    /**
     * Canonical, order-independent-of-display serialization of the finalized content. The
     * renderer may change without changing this hash; content changes must change it.
     */
    static String hash(FinalizedReview review) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("schema=").append(SCHEMA_VERSION).append('\n');
        canonical.append("review=").append(review.reference()).append('\n');
        canonical.append("conclusion=").append(review.conclusion()).append('\n');
        canonical.append("summary=").append(nullToEmpty(review.summary())).append('\n');
        canonical.append("reviewedAreas=").append(nullToEmpty(review.reviewedAreas())).append('\n');
        canonical.append("limitations=").append(nullToEmpty(review.limitations())).append('\n');
        for (FinalizedReview.Finding finding : review.findings()) {
            canonical.append("finding|").append(finding.reference()).append('|')
                    .append(finding.severity()).append('|')
                    .append(finding.category()).append('|')
                    .append(finding.title()).append('|')
                    .append(finding.explanation()).append('|')
                    .append(finding.impact()).append('|')
                    .append(finding.recommendation()).append('\n');
            for (FinalizedReview.Location location : finding.locations()) {
                canonical.append("location|").append(location.path()).append('|')
                        .append(location.side()).append('|')
                        .append(location.startLine() == null ? "" : location.startLine()).append('|')
                        .append(location.endLine() == null ? "" : location.endLine()).append('|')
                        .append(location.description()).append('\n');
            }
        }
        return Digests.sha256Hex(canonical.toString());
    }

    private ReviewRecord requireMutableDraft(long reviewId) {
        ReviewRecord review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + reviewId));
        if (!review.isMutable()) {
            throw new ReviewDraftConflictException("Review " + reviewId + " is already "
                    + review.getState(), review.getDraftRevision());
        }
        return review;
    }

    private ReviewRecord requireDraftAtRevision(long reviewId, int expectedDraftRevision) {
        ReviewRecord review = requireMutableDraft(reviewId);
        if (review.getDraftRevision() != expectedDraftRevision) {
            throw new ReviewDraftConflictException("Draft revision is " + review.getDraftRevision()
                    + ", not " + expectedDraftRevision, review.getDraftRevision());
        }
        return review;
    }

    private static void invalidateAssessment(ReviewRecord review) {
        ReviewAssessment assessment = review.getAssessment();
        if (assessment != null) {
            assessment.setAssessedRevision(ReviewAssessment.STALE_REVISION);
        }
    }

    private static List<ReviewFinding> activeFindings(ReviewRecord review) {
        return review.getFindings().stream()
                .filter(finding -> !finding.isWithdrawn())
                .sorted((left, right) -> Integer.compare(left.getOrdinal(), right.getOrdinal()))
                .toList();
    }

    private static ReviewFinding findByClientKey(ReviewRecord review, String clientKey) {
        return review.getFindings().stream()
                .filter(finding -> clientKey.equals(finding.getClientKey()))
                .findFirst().orElse(null);
    }

    private static ReviewFinding findActiveByClientKey(ReviewRecord review, String clientKey) {
        ReviewFinding finding = findByClientKey(review, clientKey);
        return finding == null || finding.isWithdrawn() ? null : finding;
    }

    private static ReviewFinding findByOrdinal(ReviewRecord review, int ordinal) {
        return review.getFindings().stream()
                .filter(finding -> finding.getOrdinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new InvalidReviewContentException("finding",
                        "Unknown finding ordinal " + ordinal));
    }

    private static int nextOrdinal(ReviewRecord review) {
        return review.getFindings().stream().mapToInt(ReviewFinding::getOrdinal).max().orElse(0) + 1;
    }

    private static void replaceLocations(ReviewFinding finding, List<LocationDraft> locations) {
        finding.getLocations().clear();
        int position = 0;
        for (LocationDraft draft : locations) {
            ReviewFindingLocation location = new ReviewFindingLocation();
            location.setFinding(finding);
            location.setPosition(position++);
            location.setPath(draft.path().trim());
            location.setSide(draft.side());
            location.setStartLine(draft.startLine());
            location.setEndLine(draft.endLine());
            location.setDescription(requireText(draft.description(), MAX_LOCATION_DESCRIPTION_CHARS,
                    "evidence"));
            finding.getLocations().add(location);
        }
    }

    private static void validateLocations(List<LocationDraft> locations) {
        for (LocationDraft location : locations) {
            if (location == null || location.side() == null) {
                throw new InvalidReviewContentException("evidence",
                        "Every source location needs a side (BASE or HEAD)");
            }
            String path = requireText(location.path(), MAX_PATH_CHARS, "path");
            if (path.startsWith("/") || path.contains("\\") || path.contains("..")) {
                throw new InvalidReviewContentException("path",
                        "Source locations must be repository-relative paths without '..': " + path);
            }
            Integer start = location.startLine();
            Integer end = location.endLine();
            if ((start != null && start < 1) || (end != null && end < 1)) {
                throw new InvalidReviewContentException("line",
                        "Line numbers start at 1: " + path);
            }
            if (end != null && start == null) {
                throw new InvalidReviewContentException("line",
                        "An end line requires a start line: " + path);
            }
            if (start != null && end != null && end < start) {
                throw new InvalidReviewContentException("line",
                        "The end line " + end + " precedes the start line " + start + ": " + path);
            }
        }
    }

    private static ReviewSeverity requireSeverity(ReviewSeverity severity) {
        if (severity == null) {
            throw new InvalidReviewContentException("severity",
                    "A severity is required (BLOCKER, MEDIUM or LOW)");
        }
        return severity;
    }

    private static <T> T requireValue(T value, String field) {
        if (value == null) {
            throw new InvalidReviewContentException(field, "A value is required");
        }
        return value;
    }

    private static String requireText(String value, int maxChars, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidReviewContentException(field, "A value is required");
        }
        if (trimmed.length() > maxChars) {
            throw new InvalidReviewContentException(field,
                    "At most " + maxChars + " characters are allowed (got " + trimmed.length() + ")");
        }
        return trimmed;
    }

    private static String optionalText(String value, int maxChars, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() > maxChars) {
            throw new InvalidReviewContentException(field,
                    "At most " + maxChars + " characters are allowed (got " + trimmed.length() + ")");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
