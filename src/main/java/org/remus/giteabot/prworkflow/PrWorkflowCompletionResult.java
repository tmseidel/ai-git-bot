package org.remus.giteabot.prworkflow;

/**
 * Result of generic required completion. {@code applied=false} is an identical terminal replay.
 * Domain owners must return on replay before adding required domain events, and must keep an
 * applied completion and those events in the same outer transaction.
 */
public record PrWorkflowCompletionResult(PrWorkflowRun run, boolean applied) {
}
