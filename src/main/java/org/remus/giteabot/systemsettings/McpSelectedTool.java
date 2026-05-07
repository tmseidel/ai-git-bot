package org.remus.giteabot.systemsettings;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@Entity
@Table(name = "mcp_selected_tools",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_selected_tool", columnNames = {
                "mcp_configuration_id", "qualified_name"
        }))
public class McpSelectedTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_configuration_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private McpConfiguration mcpConfiguration;

    @Column(nullable = false)
    private String qualifiedName;

    @Column(nullable = false)
    private String serverName;

    @Column(nullable = false)
    private String toolName;

    @Column
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;
}

