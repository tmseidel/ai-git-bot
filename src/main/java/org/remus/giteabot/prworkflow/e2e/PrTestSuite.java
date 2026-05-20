package org.remus.giteabot.prworkflow.e2e;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One generated test suite tied to a single
 * {@link org.remus.giteabot.prworkflow.PrWorkflowRun}. Created by
 * {@code E2ETestWorkflow} once the planner has produced a draft plan, then
 * populated with {@link PrTestCase} rows by the author/runner agents.
 *
 * <p>The {@code run_id} foreign key is modelled as a plain {@code Long}
 * column (no {@code @ManyToOne}) so the workflow can persist suites without
 * forcing the runtime {@code PrWorkflowRun} to be re-attached to the JPA
 * session — same pattern as {@code PrWorkflowRun.botId}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "pr_test_suites")
public class PrTestSuite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private E2eTestFramework framework;

    /**
     * Git ref the planner inspected. Captured for reproducibility and
     * surfaced in the PR comment ({@code "tests generated for HEAD abc123"}).
     */
    @Column(name = "source_tree_ref", length = 255)
    private String sourceTreeRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_mode", nullable = false, length = 32)
    private SuiteLifecycleMode lifecycleMode = SuiteLifecycleMode.EPHEMERAL;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "suite", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("path ASC")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<PrTestCase> cases = new ArrayList<>();

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (lifecycleMode == null) {
            lifecycleMode = SuiteLifecycleMode.EPHEMERAL;
        }
    }

    public void addCase(PrTestCase testCase) {
        testCase.setSuite(this);
        cases.add(testCase);
    }
}
