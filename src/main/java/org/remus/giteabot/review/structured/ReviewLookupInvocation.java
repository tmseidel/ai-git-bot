package org.remus.giteabot.review.structured;

import java.util.function.BooleanSupplier;

/**
 * Trusted scope of one published-review lookup: the pull request whose reviews may be read.
 *
 * <p>Constructed from the run context, never from tool arguments, so a lookup cannot reach another
 * bot's, host's, repository's or pull request's reviews even with a valid-looking reference.</p>
 */
public record ReviewLookupInvocation(Long gitIntegrationId, Long botId, String repoOwner,
                                     String repoName, Long prNumber, BooleanSupplier cancelled) {

    public boolean isCancelled() {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
