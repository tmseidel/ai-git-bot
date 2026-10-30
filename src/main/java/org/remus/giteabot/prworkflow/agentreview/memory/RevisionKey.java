package org.remus.giteabot.prworkflow.agentreview.memory;

/** Exact commits and SHA-256 of the complete, provider-canonical diff, before prompt truncation. */
public record RevisionKey(String headSha, String baseSha, String diffHash) {
    public RevisionKey {
        MemoryChecks.require(headSha != null && headSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid headSha");
        MemoryChecks.require(baseSha != null && baseSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid baseSha");
        MemoryChecks.require(diffHash != null && diffHash.matches("sha256:[0-9a-f]{64}"), "Invalid diffHash");
    }
}
