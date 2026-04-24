package org.remus.giteabot.session;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    @Query("SELECT s FROM ReviewSession s LEFT JOIN FETCH s.messages WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.prNumber = :prNumber")
    Optional<ReviewSession> findByRepoOwnerAndRepoNameAndPrNumber(@Param("owner") String repoOwner,
                                                                   @Param("repo") String repoName,
                                                                   @Param("prNumber") Long prNumber);

    @EntityGraph(attributePaths = "participants")
    @Query("SELECT s FROM ReviewSession s WHERE s.id = :id")
    Optional<ReviewSession> findWithParticipantsById(@Param("id") Long id);

    void deleteByRepoOwnerAndRepoNameAndPrNumber(String repoOwner, String repoName, Long prNumber);
}
