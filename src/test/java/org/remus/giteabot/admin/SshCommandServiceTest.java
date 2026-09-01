package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshCommandServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void parseEndpoint_supportsGiteaSshUrls() {
        assertEquals(new SshCommandService.SshEndpoint("gitea.example.com", 22),
                SshCommandService.parseEndpoint("git@gitea.example.com:owner/repo.git"));
        assertEquals(new SshCommandService.SshEndpoint("gitea.example.com", 2222),
                SshCommandService.parseEndpoint("ssh://git@gitea.example.com:2222/owner/repo.git"));
        assertEquals(new SshCommandService.SshEndpoint("2001:db8::1", 2222),
                SshCommandService.parseEndpoint("ssh://git@[2001:db8::1]:2222/owner/repo.git"));
        assertEquals(new SshCommandService.SshEndpoint("2001:db8::1", 22),
                SshCommandService.parseEndpoint("git@[2001:db8::1]:owner/repo.git"));
    }

    @Test
    void parseEndpoint_rejectsNonSshUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> SshCommandService.parseEndpoint("https://gitea.example.com/owner/repo.git"));
        assertThrows(IllegalArgumentException.class,
                () -> SshCommandService.parseEndpoint("git@-f/etc/hosts:owner/repo.git"));
    }

    @Test
    void parseHostKeyScan_filtersCommentsAndBuildsStableFingerprints() {
        var endpoint = new SshCommandService.SshEndpoint("gitea.example.com", 22);
        var first = SshCommandService.parseHostKeyScan(endpoint, """
                # gitea.example.com:22 SSH-2.0-OpenSSH
                gitea.example.com ssh-rsa BAUG
                gitea.example.com ssh-ed25519 AQID
                """);
        var reversed = SshCommandService.parseHostKeyScan(endpoint, """
                gitea.example.com ssh-ed25519 AQID
                gitea.example.com ssh-rsa BAUG
                """);

        assertEquals("gitea.example.com ssh-ed25519 AQID\ngitea.example.com ssh-rsa BAUG\n",
                first.knownHosts());
        assertEquals("SHA256:A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E",
                first.fingerprints().getFirst().fingerprint());
        assertEquals("SHA256:eHx5jjmlvBkQNVuubQzYejay4Q/QICqD47trAF2oNHI",
                first.fingerprints().get(1).fingerprint());
        assertEquals(first.confirmation(), reversed.confirmation());
    }

    @Test
    void parseHostKeyScan_rejectsEmptyResult() {
        var endpoint = new SshCommandService.SshEndpoint("gitea.example.com", 22);

        assertThrows(IllegalStateException.class,
                () -> SshCommandService.parseHostKeyScan(endpoint, "# no host keys\n"));
    }

    @Test
    void deleteKeyFiles_failsWhenTemporaryDirectoryCannotBeRemoved() throws IOException {
        Files.writeString(tempDir.resolve("unexpected"), "content");

        assertThrows(IllegalStateException.class, () -> SshCommandService.deleteKeyFiles(tempDir));
    }
}
