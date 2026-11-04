package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitLabPlatformTest {

    private final GitLabPlatform platform = new GitLabPlatform("https://gitlab.example.com",
            "https://gitlab.example.com", new HttpCredentials(null, "glpat-token"), null,
            new GitLabApiClient(null));

    @Test
    void formatPullRequestReference_usesMergeRequestPrefix() {
        assertEquals("!42", platform.formatPullRequestReference(42L));
    }

    @Test
    void getRepositoryRemote_rejectsSshByDefault() {
        // The default remote is HTTP only; handing git SSH credentials with it would fail
        // later with an unrelated authentication error.
        GitLabPlatform ssh = new GitLabPlatform("https://gitlab.example.com", "https://gitlab.example.com",
                new HttpCredentials(null, "glpat-token"), () -> new SshCredentials("key", "known-hosts"),
                new GitLabApiClient(null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ssh.getRepositoryRemote("group", "project"));
        assertEquals("SSH transport is not supported for GitLabPlatform", e.getMessage());
    }
}
