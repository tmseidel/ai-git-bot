package org.remus.giteabot.systemsettings;

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
 * Reusable configuration that whitelists which built-in agent tools are exposed
 * to a {@link org.remus.giteabot.admin.Bot} at runtime. Analogous to
 * {@link McpConfiguration} for remote MCP tools — but for the in-process tools
 * defined in {@link org.remus.giteabot.agent.tools.ToolCatalog} (file, context,
 * validation, writer-repository).
 *
 * <p>Exactly one configuration is flagged as the {@link #isDefaultEntry()
 * default entry}. The default configuration is created automatically at
 * startup, contains every currently known built-in tool, and is protected
 * against deletion/renaming.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bot_tool_configurations")
public class BotToolConfiguration {

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
    private List<BotToolSelection> selectedTools = new ArrayList<>();

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
