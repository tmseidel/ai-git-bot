package org.remus.giteabot.agent.session;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.remus.giteabot.session.ConversationMessage;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents an agent coding session for implementing an issue.
 * Tracks the conversation history, file changes made, and the resulting PR.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_sessions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repoOwner", "repoName", "issueNumber"}))
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repoOwner;

    @Column(nullable = false)
    private String repoName;

    @Column(nullable = false)
    private Long issueNumber;

    /**
     * The title of the issue being implemented.
     */
    private String issueTitle;

    /**
     * The name of the branch created for this implementation.
     */
    private String branchName;

    /**
     * The PR number if a pull request was created (null if not yet created).
     */
    private Long prNumber;

    /**
     * The issue number created by the technical-writer agent.
     */
    private Long generatedIssueNumber;

    /**
     * The original issue author's username/login for writer-agent follow-up checks.
     */
    private String issueAuthorUsername;

    /**
     * Distinguishes code implementation sessions from technical-writer sessions.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentSessionType sessionType = AgentSessionType.CODING;

    /**
     * Current status of the agent session.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentSessionStatus status = AgentSessionStatus.IN_PROGRESS;

    /**
     * AI conversation history for context continuity.
     * Using Set to avoid MultipleBagFetchException with Hibernate.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_session_id")
    private Set<ConversationMessage> messages = new HashSet<>();

    /**
     * Step 7.1 — short summary of the most recently parsed implementation plan,
     * persisted so PR-body / follow-up comment generation no longer needs to
     * re-parse the conversation history.
     */
    @Column(name = "last_plan_summary", length = 2048)
    private String lastPlanSummary;

    /**
     * Step 7.1 — raw JSON of the most recently parsed implementation plan.
     * <p>Mapped via {@link SqlTypes#LONG32VARCHAR} (rather than {@code @Lob})
     * so PostgreSQL produces a plain {@code text} column instead of an
     * {@code oid}/Large-Object, matching the Flyway migration (V10).
     */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "last_plan_json")
    private String lastPlanJson;

    /**
     * Step 7.1 — timestamp at which {@link #lastPlanJson} was recorded.
     */
    @Column(name = "last_plan_at")
    private Instant lastPlanAt;

    /**
     * Cumulative input tokens consumed across all AI calls in this session.
     * Used for cost monitoring only — <em>not</em> for context-window pressure,
     * since each call's input already includes the full history and summing
     * across rounds grows superlinearly.
     */
    @Column(name = "total_input_tokens", nullable = false)
    private long totalInputTokens = 0L;

    /**
     * Cumulative output tokens generated across all AI calls in this session.
     * Used for cost monitoring.
     */
    @Column(name = "total_output_tokens", nullable = false)
    private long totalOutputTokens = 0L;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
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

    public AgentSession(String repoOwner, String repoName, Long issueNumber, String issueTitle) {
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
    }

    public void addMessage(String role, String content) {
        messages.add(new ConversationMessage(role, content));
    }

    /**
     * Accumulates token usage from a single AI call.
     */
    public void accumulateTokens(long inputTokens, long outputTokens) {
        this.totalInputTokens += inputTokens;
        this.totalOutputTokens += outputTokens;
    }


    public enum AgentSessionStatus {
        /**
         * Agent is currently working on the issue.
         */
        IN_PROGRESS,

        /**
         * Agent has created a PR and is waiting for feedback.
         */
        PR_CREATED,

        /**
         * Agent is making additional changes based on feedback.
         */
        UPDATING,

        /**
         * PR was merged, session is complete.
         */
        COMPLETED,

        /**
         * Writer agent has created the improved issue.
         */
        ISSUE_CREATED,

        /**
         * Agent failed to implement the issue.
         */
        FAILED
    }

    public enum AgentSessionType {
        CODING,
        WRITER
    }
}
