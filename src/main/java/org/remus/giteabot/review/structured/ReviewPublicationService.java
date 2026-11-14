package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


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
     * Commits a {@link ReviewPublicationState#PENDING} intent for {@code outputReference} and
     * returns it. The rendered body is kept as a delivery snapshot; the review record remains the
     * source of truth.
     */
    @Transactional
    public ReviewPublication start(String outputReference, String provider, String channel,
                                   String renderedMarkdown) {
        WorkflowOutput output = outputRepository.findByReference(outputReference)
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + outputReference));
        ReviewRecord review = reviewRepository.findByOutputId(output.getId())
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Review " + outputReference + " has no record"));

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
        return repository.save(publication);
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

    /** Whether any revision of this review reached the provider, successful rerenders included. */
    @Transactional(readOnly = true)
    public boolean delivered(long reviewId) {
        return repository.existsByReviewIdAndState(reviewId, ReviewPublicationState.PUBLISHED);
    }
}
