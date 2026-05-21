package org.remus.giteabot.prworkflow.config;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One enabled {@link org.remus.giteabot.prworkflow.PrWorkflow} inside a
 * {@link WorkflowConfiguration}. The workflow is identified by its stable
 * lower-case {@link #workflowKey} as returned by
 * {@code PrWorkflow.key()}.
 *
 * <p>Workflow-specific parameters (see
 * {@link org.remus.giteabot.prworkflow.PrWorkflow#paramsSchema()}) are kept
 * as a separate {@code workflow_selection_params} child table — one row per
 * (name, value) pair — instead of an embedded JSON document. The collection
 * cascades on persist/remove and uses orphan-removal so the service code can
 * simply call {@link #replaceParams(Map)} to update the persisted state.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_selections",
        uniqueConstraints = @UniqueConstraint(name = "uk_workflow_selection", columnNames = {
                "workflow_configuration_id", "workflow_key"
        }))
public class WorkflowSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_configuration_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private WorkflowConfiguration configuration;

    /** Stable lower-case workflow identifier from {@code PrWorkflow.key()}. */
    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @OneToMany(mappedBy = "selection", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<WorkflowSelectionParam> params = new ArrayList<>();

    /**
     * Read-only view of the persisted params as a {@code name -> value} map,
     * preserving the entity insertion order.
     */
    public Map<String, String> getParamsMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (params != null) {
            for (WorkflowSelectionParam p : params) {
                map.put(p.getName(), p.getValue());
            }
        }
        return map;
    }

    /**
     * Replaces the persisted params with the given map in iteration order.
     * Existing child rows are removed via orphan-removal; new rows are
     * inserted with the cascade on {@link #params}.
     */
    public void replaceParams(Map<String, String> values) {
        if (params == null) {
            params = new ArrayList<>();
        }
        params.clear();
        if (values == null) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            params.add(new WorkflowSelectionParam(this, entry.getKey(), entry.getValue()));
        }
    }
}
