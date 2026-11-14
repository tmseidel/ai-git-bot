package org.remus.giteabot.review.structured;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The authoritative structured form of one agentic review. The published comment is a
 * rendering of this record, never its source.
 *
 * <p>{@code draftRevision} advances on every content mutation and is the optimistic
 * concurrency token the review tools pass back on their next call. Finalization freezes
 * the record: content, severity totals and the hash are computed from the persisted
 * findings, and nothing may change afterwards.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_records")
public class ReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "output_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private WorkflowOutput output;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewState state = ReviewState.DRAFT;

    /** Schema of the finding/assessment content, versioned independently of the renderer. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "base_sha", length = 64)
    private String baseSha;

    @Column(name = "head_sha", length = 64)
    private String headSha;

    @Column(name = "draft_revision", nullable = false)
    private int draftRevision;

    /** Coverage the application observed itself (truncation, skipped files); not model input. */
    @Column(name = "server_coverage_note", columnDefinition = "TEXT")
    private String serverCoverageNote;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL)
    @OrderBy("ordinal ASC")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ReviewFinding> findings = new ArrayList<>();

    @OneToOne(mappedBy = "review", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReviewAssessment assessment;

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

    public void bumpDraftRevision() {
        draftRevision++;
    }

    public boolean isMutable() {
        return state == ReviewState.DRAFT;
    }
}
