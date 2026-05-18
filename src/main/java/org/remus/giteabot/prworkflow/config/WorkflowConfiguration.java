package org.remus.giteabot.prworkflow.config;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable named whitelist of {@link org.remus.giteabot.prworkflow.PrWorkflow
 * PrWorkflows} that may run on a bot's pull-request webhook events. Analogous
 * to {@link org.remus.giteabot.systemsettings.BotToolConfiguration} but for
 * the pluggable PR workflows introduced in M1/M2.
 *
 * <p>Exactly one configuration is flagged as the {@link #isDefaultEntry()
 * default entry}; the default row is seeded by Flyway migration
 * {@code V15__workflow_configurations_default.sql} and is protected against
 * deletion/renaming.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_configurations")
public class WorkflowConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Marks the single, non-deletable default configuration. */
    @Column(name = "default_entry", nullable = false)
    private boolean defaultEntry;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "configuration", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<WorkflowSelection> selectedWorkflows = new ArrayList<>();

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

