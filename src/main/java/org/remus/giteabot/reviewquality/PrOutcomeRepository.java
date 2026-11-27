package org.remus.giteabot.reviewquality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrOutcomeRepository extends JpaRepository<PrOutcome, Long> {

    Optional<PrOutcome> findByGitIntegrationIdAndBotIdAndRepoOwnerAndRepoNameAndPrNumber(
            Long gitIntegrationId, Long botId, String repoOwner, String repoName, Long prNumber);
}
