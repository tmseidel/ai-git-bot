package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Receipt of one review-tool operation, keyed by the caller's {@code operationId}.
 *
 * <p>Same operation id with the same canonical arguments replays this receipt instead of
 * applying the mutation twice; the same id with different arguments is a conflict. The row is
 * written in the transaction that performed the mutation, so a failed mutation leaves neither a
 * finding nor a receipt claiming one.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "review_tool_operations",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_tool_operations_review_operation",
                columnNames = {"review_id", "operation_id"}))
public class ReviewToolOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "operation_id", nullable = false, length = 128)
    private String operationId;

    @Column(nullable = false, length = 64)
    private String tool;

    @Column(name = "argument_hash", nullable = false, length = 64)
    private String argumentHash;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
