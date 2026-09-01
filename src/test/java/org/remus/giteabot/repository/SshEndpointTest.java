package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshEndpointTest {

    @Test
    void parse_supportsSshAndScpRemotes() {
        assertThat(SshEndpoint.parse("git@gitea.example.com:owner/repo.git"))
                .isEqualTo(new SshEndpoint("gitea.example.com", 22));
        assertThat(SshEndpoint.parse("ssh://git@gitea.example.com:2222/owner/repo.git"))
                .isEqualTo(new SshEndpoint("gitea.example.com", 2222));
        assertThat(SshEndpoint.parse("git@[2001:db8::1]:owner/repo.git"))
                .isEqualTo(new SshEndpoint("2001:db8::1", 22));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "https://gitea.example.com/owner/repo.git", "file:///tmp/repo.git",
            "/tmp/repo.git", "C:/repos/repo.git", "C:../repo.git", "../repo.git", "-uploader",
            "git@-f/etc/hosts:owner/repo.git", "git@gitea.example.com", "ssh:///owner/repo.git",
            "SSH://git@gitea.example.com/owner/repo.git",
            "ssh://%20-option@gitea.example.com/owner/repo.git",
            "ssh://-option@gitea.example.com/owner/repo.git", "ext::owner/repo.git",
            "git@[gitea.example.com:owner/repo.git", "git@host]:owner/repo.git",
            "git@[host]suffix:owner/repo.git"
    })
    void parse_rejectsNonSshAndMalformedRemotes(String remote) {
        assertThatThrownBy(() -> SshEndpoint.parse(remote))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromKnownHosts_returnsSingleCanonicalEndpoint() {
        assertThat(SshEndpoint.fromKnownHosts("""
                # scanned host keys
                [gitea.example.com]:2222 ssh-ed25519 AQID
                [gitea.example.com]:2222 ssh-rsa BAUG
                """))
                .contains(new SshEndpoint("gitea.example.com", 2222));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "|1|hash|salt ssh-ed25519 AQID",
            "*.example.com ssh-ed25519 AQID",
            "one.example.com ssh-ed25519 AQID\ntwo.example.com ssh-ed25519 BAUG"
    })
    void fromKnownHosts_doesNotGuessManualOrMultipleEndpoints(String knownHosts) {
        assertThat(SshEndpoint.fromKnownHosts(knownHosts)).isEmpty();
    }
}
