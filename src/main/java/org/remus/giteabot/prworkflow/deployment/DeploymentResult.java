package org.remus.giteabot.prworkflow.deployment;

/**
 * Outcome of {@link DeploymentStrategy#trigger(DeploymentRequest)}.
 *
 * <p>A strategy that resolves the preview URL synchronously (e.g. the
 * {@code STATIC} strategy after a readiness probe) returns
 * {@code status = READY} together with the URL. A strategy that hands off to
 * a remote CI and waits for a callback returns {@code status = PENDING}; the
 * {@link DeploymentOrchestrator} will then block on the per-run notification
 * queue until the inbound callback arrives or the target timeout elapses.</p>
 *
 * @param status              current status reported by the strategy
 * @param previewUrl          preview URL when {@code status == READY}, may be {@code null} otherwise
 * @param handleJson          opaque-to-the-bot state to round-trip into the run record;
 *                            handed back to {@code poll()} / {@code teardown()}
 * @param errorMessage        human-readable error when {@code status} is {@link DeploymentStatus#FAILED}
 *                            or {@link DeploymentStatus#REJECTED}
 */
public record DeploymentResult(
        DeploymentStatus status,
        String previewUrl,
        String handleJson,
        String errorMessage) {

    public static DeploymentResult ready(String previewUrl, String handleJson) {
        return new DeploymentResult(DeploymentStatus.READY, previewUrl, handleJson, null);
    }

    public static DeploymentResult pending(String handleJson) {
        return new DeploymentResult(DeploymentStatus.PENDING, null, handleJson, null);
    }

    public static DeploymentResult failed(String errorMessage, String handleJson) {
        return new DeploymentResult(DeploymentStatus.FAILED, null, handleJson, errorMessage);
    }

    public static DeploymentResult rejected(String errorMessage) {
        return new DeploymentResult(DeploymentStatus.REJECTED, null, null, errorMessage);
    }
}
