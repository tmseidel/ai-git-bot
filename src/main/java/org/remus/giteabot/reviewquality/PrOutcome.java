package org.remus.giteabot.reviewquality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * How a pull request a bot was told about ended: merged or closed, and when. Written from the
 * pull-request-closed webhook; a reopen sets it back to open. Owner and repository are stored in
 * lower case, as the Review quality page compares them.
 */
@Data
@Entity
@Table(name = "pr_outcomes", uniqueConstraints = @UniqueConstraint(name = "uk_pr_outcomes_pr",
        columnNames = {"git_integration_id", "bot_id", "repo_owner", "repo_name", "pr_number"}))
public class PrOutcome {

    public enum State {
        OPEN,
        MERGED,
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "git_integration_id", nullable = false)
    private Long gitIntegrationId;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false, length = 255)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false, length = 255)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
