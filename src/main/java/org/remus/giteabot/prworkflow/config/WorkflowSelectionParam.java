package org.remus.giteabot.prworkflow.config;

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
 * One persisted parameter (name/value pair) belonging to a
 * {@link WorkflowSelection}. Replaces the former {@code params_json} TEXT
 * column on {@code workflow_selections} so the parameter store is properly
 * relational, queryable, indexable and free of any embedded JSON parsing.
 *
 * <p>Values are stored as plain strings; type coercion against the owning
 * workflow's {@link org.remus.giteabot.prworkflow.WorkflowParamsSchema}
 * happens in {@link WorkflowParamsValidator} on read and on save.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_selection_params",
        uniqueConstraints = @UniqueConstraint(name = "uk_workflow_selection_param", columnNames = {
                "workflow_selection_id", "name"
        }))
public class WorkflowSelectionParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_selection_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private WorkflowSelection selection;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "param_value", columnDefinition = "TEXT")
    private String value;

    public WorkflowSelectionParam(WorkflowSelection selection, String name, String value) {
        this.selection = selection;
        this.name = name;
        this.value = value;
    }
}

