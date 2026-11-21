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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One finding of a structured review.
 *
 * <p>{@code ordinal} is assigned by the application on first submission and is the
 * public {@code O1234-F<ordinal>} identity. Rendering order, severity changes and later
 * corrections never renumber it, so feedback stays attached to the same finding.
 * {@code clientKey} is the draft-local key the model uses for idempotent upserts.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_findings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_review_findings_review_ordinal",
                        columnNames = {"review_id", "ordinal"}),
                @UniqueConstraint(name = "uk_review_findings_review_client_key",
                        columnNames = {"review_id", "client_key"})
        })
public class ReviewFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReviewRecord review;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "client_key", nullable = false, length = 128)
    private String clientKey;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewSeverity severity;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String impact;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(nullable = false)
    private boolean withdrawn;

    @Column(name = "withdrawal_reason", length = 1024)
    private String withdrawalReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "finding", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ReviewFindingLocation> locations = new ArrayList<>();

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
