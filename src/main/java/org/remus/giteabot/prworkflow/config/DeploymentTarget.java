package org.remus.giteabot.prworkflow.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;

import java.time.Instant;

/**
 * Operator-managed configuration of one
 * {@link org.remus.giteabot.prworkflow.deployment.DeploymentStrategy}.
 *
 * <p>{@code configJson} is the strategy-specific configuration document and
 * is persisted encrypted at rest by
 * {@link org.remus.giteabot.admin.EncryptionService} when an
 * {@code APP_ENCRYPTION_KEY} is configured — the column holds the cipher
 * text, the service decrypts on read.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "deployment_targets")
public class DeploymentTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 32)
    private DeploymentStrategyType strategyType;

    /**
     * Encrypted-at-rest strategy configuration. The cleartext value is a
     * JSON document whose shape is determined by the strategy
     * implementation; see {@code doc/PR_WORKFLOWS.md} § "Deployment targets"
     * for the per-strategy schemas.
     */
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    /**
     * Optional template used by the {@code STATIC} strategy and surfaced in
     * comments/dashboards for asynchronous strategies. Supports the
     * placeholders {@code {prNumber}}, {@code {sha}} and {@code {branch}}.
     */
    @Column(name = "preview_url_template", length = 1024)
    private String previewUrlTemplate;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 600;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
}
