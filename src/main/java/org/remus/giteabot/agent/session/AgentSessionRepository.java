package org.remus.giteabot.agent.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    @Query("SELECT s FROM AgentSession s LEFT JOIN FETCH s.messages " +
           "WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.issueNumber = :issueNumber")
    Optional<AgentSession> findByRepoOwnerAndRepoNameAndIssueNumber(
            @Param("owner") String repoOwner,
            @Param("repo") String repoName,
            @Param("issueNumber") Long issueNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentSession s " +
           "WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.issueNumber = :issueNumber")
    Optional<AgentSession> findByRepoOwnerAndRepoNameAndIssueNumberForUpdate(
            @Param("owner") String repoOwner,
            @Param("repo") String repoName,
            @Param("issueNumber") Long issueNumber);

    @Query("SELECT s FROM AgentSession s LEFT JOIN FETCH s.messages " +
           "WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.prNumber = :prNumber")
    Optional<AgentSession> findByRepoOwnerAndRepoNameAndPrNumber(
            @Param("owner") String repoOwner,
            @Param("repo") String repoName,
            @Param("prNumber") Long prNumber);

    void deleteByRepoOwnerAndRepoNameAndIssueNumber(String repoOwner, String repoName, Long issueNumber);
}
