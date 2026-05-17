package org.remus.giteabot.systemsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One enabled built-in tool inside a {@link BotToolConfiguration}. The tool is
 * identified by its stable lower-case {@link #toolName} as registered in
 * {@link org.remus.giteabot.agent.tools.ToolCatalog}. {@link #toolKind} is a
 * snapshot of the catalog category for display/grouping in the admin UI and
 * for diagnostics — it is not authoritative at runtime.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bot_tool_selections",
        uniqueConstraints = @UniqueConstraint(name = "uk_bot_tool_selection", columnNames = {
                "configuration_id", "tool_name"
        }))
public class BotToolSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "configuration_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BotToolConfiguration configuration;

    /** Stable lower-case tool identifier from the {@code ToolCatalog}. */
    @Column(name = "tool_name", nullable = false)
    private String toolName;

    /** Snapshot of the {@code ToolKind} (CONTEXT, FILE, VALIDATION, REPOSITORY). */
    @Column(name = "tool_kind", nullable = false, length = 32)
    private String toolKind;
}
