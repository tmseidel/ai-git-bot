package org.remus.giteabot.repository;

/**
 * Value helpers shared by the provider clients that resolve a pull-request head
 * ({@code GiteaApiClient}, {@code GitHubApiClient}, {@code GitLabApiClient}).
 * <p>
 * Each provider parses its own payload, but the three rules below are the same
 * everywhere and are what makes the parsed head safe to clone from — a change to
 * one of them must reach every provider. They live here, below the provider
 * clients of the {@code repository} tier, instead of in each client.
 */
public final class PullRequestHeads {

    private static final String HEADS_PREFIX = "refs/heads/";

    private PullRequestHeads() {
    }

    /**
     * Returns the bare branch name of a Git ref by stripping a {@code refs/heads/}
     * prefix.
     *
     * <p>Only the branch namespace is stripped: a pull-request head is a branch,
     * and a caller comparing a {@code refs/tags/} value against it must see the
     * untouched ref rather than a name that silently lost its namespace.</p>
     *
     * @return the bare branch name, or {@code null} when {@code ref} is null or blank
     */
    public static String normalizeBranch(String ref) {
        String branch = nonBlank(ref);
        return branch != null && branch.startsWith(HEADS_PREFIX)
                ? branch.substring(HEADS_PREFIX.length())
                : branch;
    }

    /**
     * Returns {@code value} unchanged, or {@code null} when it is null or blank.
     * Provider payloads report an absent coordinate as an empty string just as
     * often as they omit the property, and both mean "not provided".
     */
    public static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Returns {@code value} unchanged, or fails when it is null or blank — used for
     * the coordinates a workspace cannot be cloned from.
     *
     * @throws IllegalStateException with {@code message} when {@code value} is blank
     */
    public static String requiredString(String value, String message) {
        String resolved = nonBlank(value);
        if (resolved == null) {
            throw new IllegalStateException(message);
        }
        return resolved;
    }
}
