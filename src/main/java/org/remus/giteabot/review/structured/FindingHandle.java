package org.remus.giteabot.review.structured;

/** Identity of one finding after an upsert, so the caller can reference it in later calls. */
public record FindingHandle(int ordinal, String reference, int revision) {
}
