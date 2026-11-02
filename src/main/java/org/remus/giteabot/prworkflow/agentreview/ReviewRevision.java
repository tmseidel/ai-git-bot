package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Exact source, target and complete provider diff captured for one review run. */
public record ReviewRevision(String headSha, String baseSha, String diffHash) {
    public ReviewRevision {
        if (headSha == null || !headSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || baseSha == null || !baseSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || diffHash == null || !diffHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Incomplete review revision");
        }
    }

    record Capture(ReviewRevision revision, String headRef, String diff) { }

    static Capture capture(RepositoryApiClient client, String owner, String repo, Long number) {
        PullRequestDetails before = details(client, owner, repo, number);
        String diff = Objects.requireNonNull(client.getPullRequestDiff(owner, repo, number), "Missing PR diff");
        PullRequestDetails after = details(client, owner, repo, number);
        if (!Objects.equals(before.headSha(), after.headSha())
                || !Objects.equals(before.baseSha(), after.baseSha())
                || !Objects.equals(before.headRef(), after.headRef())
                || !Objects.equals(before.baseRef(), after.baseRef())) {
            throw new IllegalStateException("Pull request changed during revision capture");
        }
        try {
            String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(diff.getBytes(StandardCharsets.UTF_8)));
            return new Capture(new ReviewRevision(before.headSha(), before.baseSha(), hash), before.headRef(), diff);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static PullRequestDetails details(RepositoryApiClient client, String owner, String repo, Long number) {
        PullRequestDetails details = client.getPullRequestDetails(owner, repo, number)
                .orElseThrow(() -> new IllegalStateException("Provider cannot capture the review revision"));
        if (details.state() != PullRequestState.OPEN || details.headRef() == null || details.headRef().isBlank()) {
            throw new IllegalStateException("Pull request is not open or has no source ref");
        }
        return details;
    }

    String header() {
        return "**Reviewed revision:** head `" + headSha + "`, base `" + baseSha
                + "`, diff `" + diffHash + "`.\n\nThis comments on that captured revision; it is not an approval of the current PR.\n\n";
    }
}
