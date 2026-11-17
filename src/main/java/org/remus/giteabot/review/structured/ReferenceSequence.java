package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Counter row per public-reference namespace ({@code O}, later {@code FB}/{@code L}).
 *
 * <p>A table counter instead of a database sequence keeps the allocation portable: H2 and
 * PostgreSQL disagree on sequence syntax, both support {@code SELECT ... FOR UPDATE}.
 * Rows are seeded by the migration, so allocation only ever increments an existing
 * row inside the creating transaction — a reference is therefore reserved before it is
 * ever rendered or acknowledged.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reference_sequences")
public class ReferenceSequence {

    @Id
    @Column(nullable = false, length = 32)
    private String name;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
