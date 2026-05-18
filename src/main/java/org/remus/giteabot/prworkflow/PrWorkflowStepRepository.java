package org.remus.giteabot.prworkflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrWorkflowStepRepository extends JpaRepository<PrWorkflowStep, Long> {
}

