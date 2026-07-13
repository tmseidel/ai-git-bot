package org.remus.giteabot.prworkflow.unittest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UnitTestSuiteRepository extends JpaRepository<UnitTestSuite, Long> {

    /**
     * Eager-loads the suite together with its {@link UnitTestSuite#getCases()}
     * collection. Required by callers that consume the cases <em>after</em> the
     * surrounding transaction has closed (the async workflow orchestrator thread).
     */
    @Query("SELECT s FROM UnitTestSuite s LEFT JOIN FETCH s.cases WHERE s.id = :id")
    Optional<UnitTestSuite> findByIdWithCases(@Param("id") Long id);
}

