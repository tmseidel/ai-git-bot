package org.remus.giteabot.prworkflow.e2e;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrTestCaseRepository extends JpaRepository<PrTestCase, Long> {

    /**
     * Finds the case for the given path inside the given suite, or empty if
     * the suite has not yet seen that path. Used by {@code pr-test-write} to
     * decide between insert and update.
     */
    Optional<PrTestCase> findBySuiteAndPath(PrTestSuite suite, String path);

    /** All cases of the suite, in id order. Used by the runner agent. */
    List<PrTestCase> findBySuiteOrderByIdAsc(PrTestSuite suite);
}

