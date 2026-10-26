package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic pull-request details used to hydrate webhook payloads that
 * lack the pull-request object (e.g. GitHub {@code issue_comment} events).
 *
 * @param title   pull request title, may be {@code null}
 * @param body    pull request description, may be {@code null}
 * @param state   lifecycle state mapped by the provider, {@code null} when the
 *                provider did not report enough to determine it
 * @param headRef source branch ref as reported by the provider, may be {@code null}
 * @param headSha current source commit, may be {@code null}
 * @param baseRef target branch ref as reported by the provider, may be {@code null}
 * @param baseSha target branch commit as reported by the provider, may be {@code null}
 */
public record PullRequestDetails(String title, String body, PullRequestState state,
                                 String headRef, String headSha, String baseRef, String baseSha) {
}
