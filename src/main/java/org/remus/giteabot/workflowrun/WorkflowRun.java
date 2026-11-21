package org.remus.giteabot.workflowrun;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.ai.TriggerKind;

import java.time.Instant;

/**
 * One triggered workflow (a review, an Issue Writer run, ...) and the totals of the AI
 * interactions it made. Every {@link org.remus.giteabot.aiusage.AiInteraction} belongs to
 * exactly one run.
 *
 * <p>Bot, integration and repository are stored as plain names (with a soft {@code botId}), so
 * a run stays readable after the bot or an integration is deleted. Only facts about the run itself
 * are stored; its totals (rounds, tokens, tool calls, model and tool time) are computed from its
 * interactions and tool calls.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_type", nullable = false, length = 64)
    private String workflowType;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "bot_name")
    private String botName;

    @Column(name = "ai_integration_name")
    private String aiIntegrationName;

    @Column(name = "git_integration_name")
    private String gitIntegrationName;

    @Column(name = "repo_owner")
    private String repoOwner;

    @Column(name = "repo_name")
    private String repoName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", length = 32)
    private TriggerKind triggerKind;

    @Column(name = "trigger_number")
    private Long triggerNumber;

    /** The audit session id ({@code owner/repo#n}) the run was triggered for. */
    @Column(name = "session_id")
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus state = RunStatus.RUNNING;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** Start of the run's first AI interaction. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** An agent loop of this run ran out of rounds before the model finished. */
    @Column(name = "round_limit_reached", nullable = false)
    private boolean roundLimitReached;
}
