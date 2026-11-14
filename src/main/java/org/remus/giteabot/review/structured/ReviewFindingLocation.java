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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Typed source location supporting one finding. The commit identity is the review's
 * pinned head/base sha, so the location itself stays a pure reference.
 *
 * <p>Line bounds are optional (a whole-file or repository-wide reference has none). A
 * location that falls outside the diff is still rendered as a source link — it must
 * never be silently dropped.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_finding_locations",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_finding_locations_position",
                columnNames = {"finding_id", "position"}))
public class ReviewFindingLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReviewFinding finding;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 1024)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ReviewSide side;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;
}
