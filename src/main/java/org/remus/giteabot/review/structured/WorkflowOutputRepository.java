package org.remus.giteabot.review.structured;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowOutputRepository extends JpaRepository<WorkflowOutput, Long> {

    Optional<WorkflowOutput> findByReference(String reference);
}
