package org.remus.giteabot.prworkflow;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fills in pull-request data missing from a webhook payload using
 * {@link RepositoryApiClient#getPullRequestDetails}.
 *
 * <p>Background: GitHub fires {@code issue_comment} events for slash commands
 * on pull-request conversations, but the payload only carries an {@code issue}
 * object — no {@code pull_request}. PR workflows need the head branch to clone,
 * commit and dispatch against, so it is fetched from the provider API.</p>
 */
@Slf4j
public final class PrPayloadHydrator {

    private PrPayloadHydrator() {
    }

    /**
     * Populates {@code payload.pullRequest} (number, title, body, state, head ref / SHA,
     * base ref / SHA) from the provider API. No-op when the payload already has a head ref, when
     * the repository or PR number cannot be determined, or when the provider does
     * not support pull-request details.
     *
     * <p>Never throws: a failure to obtain the client or fetch the details is
     * logged and leaves the payload untouched, so callers can proceed with what
     * the webhook delivered.</p>
     *
     * @param clientSupplier supplies the API client; only invoked when a fetch is needed
     */
    public static void hydrate(WebhookPayload payload, Supplier<RepositoryApiClient> clientSupplier) {
        if (hasHeadRef(payload)) {
            return;
        }
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = resolvePrNumber(payload);
        if (prNumber == null || prNumber <= 0) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Optional<PullRequestDetails> details;
        try {
            details = clientSupplier.get().getPullRequestDetails(owner, repo, prNumber);
        } catch (RuntimeException e) {
            log.warn("Could not hydrate PR details for {}/{}#{}: {}", owner, repo, prNumber, e.getMessage());
            return;
        }
        if (details.isEmpty()) {
            log.debug("getPullRequestDetails returned nothing for {}/{}#{} — provider may not support hydration",
                    owner, repo, prNumber);
            return;
        }
        PullRequestDetails pr = details.get();
        WebhookPayload.PullRequest target = payload.getPullRequest();
        if (target == null) {
            target = new WebhookPayload.PullRequest();
            payload.setPullRequest(target);
        }
        target.setNumber(prNumber);
        if (pr.title() != null) target.setTitle(pr.title());
        if (pr.body() != null) target.setBody(pr.body());
        if (pr.state() != null) {
            // The payload uses the Gitea/GitHub convention: merged PRs are "closed" with merged=true
            target.setState(pr.state() == PullRequestState.OPEN ? "open" : "closed");
            target.setMerged(pr.state() == PullRequestState.MERGED);
        }
        if (pr.headRef() != null || pr.headSha() != null) {
            WebhookPayload.Head head = new WebhookPayload.Head();
            head.setRef(pr.headRef());
            head.setSha(pr.headSha());
            target.setHead(head);
        }
        if (pr.baseRef() != null || pr.baseSha() != null) {
            WebhookPayload.Head base = new WebhookPayload.Head();
            base.setRef(pr.baseRef());
            base.setSha(pr.baseSha());
            target.setBase(base);
        }
        log.info("Hydrated PR #{} for {}/{} — head={} sha={} base={}",
                prNumber, owner, repo, pr.headRef(), pr.headSha(), pr.baseRef());
    }

    /**
     * Resolves the PR head branch. Prefers the webhook payload; when the head ref
     * is missing it authoritatively re-fetches it from the provider API.
     *
     * @return the normalised head branch, or {@code null} when it cannot be
     *         determined — callers MUST skip rather than substitute the
     *         repository default branch
     */
    public static String resolveHeadBranch(RepositoryApiClient client, WebhookPayload payload,
                                           String owner, String repo, long prNumber) {
        if (hasHeadRef(payload)) {
            return BranchRefs.normalize(payload.getPullRequest().getHead().getRef());
        }
        if (prNumber <= 0) {
            return null;
        }
        try {
            return client.getPullRequestDetails(owner, repo, prNumber)
                    .map(PullRequestDetails::headRef)
                    .map(BranchRefs::normalize)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("getPullRequestDetails failed for {}/{}#{}: {}",
                    owner, repo, prNumber, e.getMessage());
            return null;
        }
    }

    private static boolean hasHeadRef(WebhookPayload payload) {
        return payload.getPullRequest() != null
                && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank();
    }

    /**
     * Resolves the pull-request number: the {@code pull_request} object first, then
     * the {@code issue} (GitHub {@code issue_comment} events carry no pull-request
     * block), then the top-level {@code number} field.
     *
     * @return the PR number, or {@code null} when the payload carries none
     */
    public static Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        return payload.getNumber();
    }

    /**
     * Like {@link #resolvePrNumber}, but returns {@code 0} when the payload carries
     * no PR number.
     */
    public static long resolvePrNumberOrZero(WebhookPayload payload) {
        Long prNumber = resolvePrNumber(payload);
        return prNumber == null ? 0L : prNumber;
    }
}
