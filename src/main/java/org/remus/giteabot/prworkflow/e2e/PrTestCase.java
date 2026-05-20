package org.remus.giteabot.prworkflow.e2e;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * One generated test file inside a {@link PrTestSuite}. The full file
 * {@link #content} is stored inline so the bot can re-run or regenerate the
 * test without re-cloning anything.
 *
 * <p>{@link #lastStatus} is updated by the runner after every execution;
 * {@link #lastLog} stores the last (truncated) stdout/stderr excerpt. Both
 * are also reflected in the Markdown summary posted to the PR.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "pr_test_cases")
public class PrTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "suite_id", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private PrTestSuite suite;

    /** Relative path inside the workspace, e.g. {@code tests/login.spec.ts}. */
    @Column(nullable = false, length = 1024)
    private String path;

    /** Optional human-readable title from the planner; may be {@code null}. */
    @Column(length = 512)
    private String title;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 16)
    private PrTestCaseStatus lastStatus = PrTestCaseStatus.PENDING;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_duration_ms")
    private Long lastDurationMs;

    @Column(name = "last_log", columnDefinition = "CLOB")
    private String lastLog;
}
