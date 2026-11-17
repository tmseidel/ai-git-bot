package org.remus.giteabot.review.structured;

/**
 * Which revision of the pull request a finding's source location refers to. A deleted
 * file exists only on {@link #BASE}, so locations must state their side explicitly.
 */
public enum ReviewSide {
    BASE, HEAD
}
