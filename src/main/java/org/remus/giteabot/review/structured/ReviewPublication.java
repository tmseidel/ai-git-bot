package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One delivery attempt of a finalized review to a pull request.
 *
 * <p>The intent is written before the Git call so a crash mid-delivery leaves a visible
 * {@link ReviewPublicationState#PENDING} row instead of a silently missing review. Each rerender is
 * a new revision: its rendered Markdown is kept as a delivery snapshot only — the review record
 * stays the source of truth, and rerendering never changes a finding's identity.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_publications",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_publications_review_revision",
                columnNames = {"review_id", "render_revision"}),
        indexes = @Index(name = "idx_review_publications_state", columnList = "state"))
public class ReviewPublication {

    /** Renderer contract version; bump when the layout changes in a way consumers may notice. */
    public static final int RENDERER_VERSION = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    /** Provider / channel the review was delivered to (never a secret). */
    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 64)
    private String channel;

    @Column(name = "renderer_version", nullable = false)
    private int rendererVersion = RENDERER_VERSION;

    @Column(name = "render_revision", nullable = false)
    private int renderRevision = 1;

    @Column(name = "render_hash", nullable = false, length = 64)
    private String renderHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewPublicationState state = ReviewPublicationState.PENDING;

    /** Provider receipt, when the API returns one; most review APIs return {@code void}. */
    @Column(name = "provider_receipt", length = 255)
    private String providerReceipt;

    /** Delivery snapshot of the rendered comment; regenerable, never the review's source. */
    @Column(name = "rendered_markdown", columnDefinition = "TEXT")
    private String renderedMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
