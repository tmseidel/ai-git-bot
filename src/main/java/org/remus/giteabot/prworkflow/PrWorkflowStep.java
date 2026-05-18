package org.remus.giteabot.prworkflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Append-only progress / error log entry attached to one {@link PrWorkflowRun}.
 *
 * <p>Workflow implementations create steps via
 * {@link PrWorkflowContext#appendStep(String, String)}. The orchestrator also
 * persists a synthetic final step on errors so post-mortem analysis does not
 * require correlating against application logs.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "pr_workflow_steps")
public class PrWorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private PrWorkflowRun run;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "log_excerpt", columnDefinition = "TEXT")
    private String logExcerpt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

