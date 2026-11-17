package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


/**
 * Records delivery attempts of a finalized review.
 *
 * <p>Each method is its own transaction: the intent must be committed before the Git call, and the
 * outcome must be committed after it, even though the process may die in between.</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewPublicationService {

    private final ReviewPublicationRepository repository;
    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;

    /**
     * The result of {@link #start}: either the committed intent, or the state of an earlier
     * delivery that rules a new one out.
     */
    public record Start(ReviewPublication publication, ReviewPublicationState blockedBy) {

        static Start started(ReviewPublication publication) {
            return new Start(publication, null);
        }

        static Start blocked(ReviewPublicationState state) {
            return new Start(null, state);
        }

        public boolean isStarted() {
            return publication != null;
        }
    }

    /**
     * Commits a {@link ReviewPublicationState#PENDING} intent for {@code outputReference}, unless an
     * earlier delivery rules it out. The rendered body is kept as a delivery snapshot; the review
     * record remains the source of truth.
     * <p>
     * A {@code PUBLISHED} delivery always blocks. A {@code PENDING} or {@code UNCERTAIN} one may
     * already be on the pull request — a crash after the Git call, a timeout, or a delivery still
     * running — so it blocks too, unless a person confirmed nothing arrived: those rows then become
     * {@code FAILED}. {@code FAILED} and {@code SUPPRESSED} deliveries never reached the pull request
     * and do not block. The review row is locked while deciding, so two concurrent deliveries of one
     * review cannot both start.
     *
     * @param confirmedNotDelivered a person checked the pull request and found no earlier copy
     */
    @Transactional
    public Start start(String outputReference, String provider, String channel,
                       String renderedMarkdown, boolean confirmedNotDelivered) {
        WorkflowOutput output = outputRepository.findByReference(outputReference)
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + outputReference));
        ReviewRecord review = reviewRepository.findForUpdateByOutputId(output.getId())
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Review " + outputReference + " has no record"));

        List<ReviewPublication> earlier = repository.findByReviewIdOrderByRenderRevisionAsc(review.getId());
        if (earlier.stream().anyMatch(previous -> previous.getState() == ReviewPublicationState.PUBLISHED)) {
            return Start.blocked(ReviewPublicationState.PUBLISHED);
        }
        List<ReviewPublication> unresolved = earlier.stream()
                .filter(previous -> previous.getState() == ReviewPublicationState.PENDING
                        || previous.getState() == ReviewPublicationState.UNCERTAIN)
                .toList();
        if (!unresolved.isEmpty()) {
            if (!confirmedNotDelivered) {
                return Start.blocked(unresolved.stream()
                        .anyMatch(previous -> previous.getState() == ReviewPublicationState.UNCERTAIN)
                        ? ReviewPublicationState.UNCERTAIN : ReviewPublicationState.PENDING);
            }
            unresolved.forEach(previous -> previous.setState(ReviewPublicationState.FAILED));
        }

        ReviewPublication publication = new ReviewPublication();
        publication.setReviewId(review.getId());
        publication.setOutputId(output.getId());
        publication.setProvider(provider);
        publication.setChannel(channel);
        publication.setRenderRevision(repository
                .findTopByReviewIdOrderByRenderRevisionDesc(review.getId())
                .map(previous -> previous.getRenderRevision() + 1)
                .orElse(1));
        publication.setRenderHash(Digests.sha256Hex(renderedMarkdown));
        publication.setRenderedMarkdown(renderedMarkdown);
        publication.setState(ReviewPublicationState.PENDING);
        return Start.started(repository.save(publication));
    }

    @Transactional
    public ReviewPublication finish(long publicationId, ReviewPublicationState state,
                                    String providerReceipt) {
        ReviewPublication publication = repository.findById(publicationId)
                .orElseThrow(() -> new InvalidReviewContentException("publication",
                        "Unknown publication " + publicationId));
        publication.setState(state);
        if (providerReceipt != null && !providerReceipt.isBlank()) {
            publication.setProviderReceipt(providerReceipt);
        }
        return publication;
    }
}
