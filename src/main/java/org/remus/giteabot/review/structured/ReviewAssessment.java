package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * The overall assessment of one review, bound to the exact finding set it judges via
 * {@code assessedRevision}. A later finding edit sets {@link #STALE_REVISION}, so the
 * stale judgement is visibly invalid instead of silently kept or deleted.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_assessments_review",
                columnNames = "review_id"))
public class ReviewAssessment {

    /** {@code assessedRevision} marker for an assessment invalidated by a finding edit. */
    public static final int STALE_REVISION = -1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReviewRecord review;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewConclusion conclusion;

    @Column(name = "reviewed_areas", columnDefinition = "TEXT")
    private String reviewedAreas;

    @Column(columnDefinition = "TEXT")
    private String limitations;

    @Column(name = "assessed_revision", nullable = false)
    private int assessedRevision;

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
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
