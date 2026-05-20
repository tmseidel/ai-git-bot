package org.remus.giteabot.prworkflow.e2e;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrTestSuiteRepository extends JpaRepository<PrTestSuite, Long> {

    List<PrTestSuite> findByPrNumberAndLifecycleMode(Long prNumber, SuiteLifecycleMode lifecycleMode);
}
