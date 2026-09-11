package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.repository.SshEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SshCommandServiceTest {
    @TempDir Path directory;

    @Test
    void scan_canonicalizesOrderAndDuplicatesAndHashesDecodedKey() {
        var endpoint = new SshEndpoint("gitea.example.com", 2222);
        String first = "[gitea.example.com]:2222 ssh-ed25519 AQID";
        String second = "[gitea.example.com]:2222 ssh-rsa BAUG";
        var scan = SshCommandService.parseHostKeyScan(endpoint, "# banner\n" + first + "\n" + second + "\n" + first);
        assertEquals(scan, SshCommandService.parseHostKeyScan(endpoint, second + "\n" + first));
        assertEquals(2, scan.fingerprints().size());
        assertEquals("SHA256:A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E", scan.fingerprints().getFirst().fingerprint());
        assertNotEquals(scan.confirmation(), SshCommandService.parseHostKeyScan(endpoint,
                "[gitea.example.com]:2222 ssh-ed25519 BAUG").confirmation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"git@-evil:repo.git", "ssh://git@host:0/repo", "https://host/repo",
            "git@host,other:repo", "git@host:repo\n-oProxyCommand=evil", "git@*:repo"})
    void scan_rejectsUntrustedEndpointsBeforeCommand(String remote) {
        assertThrows(IllegalArgumentException.class, () -> new SshCommandService().scanHostKeys(remote));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# banner only", "other.example.com ssh-ed25519 AQID",
            "*.example.com ssh-ed25519 AQID", "gitea.example.com ssh-ed25519 invalid!",
            "gitea.example.com ssh-ed25519 AQID extra", "gitea.example.com unknown AQID"})
    void scan_rejectsMissingMalformedOrMismatchedHostKeys(String output) {
        assertThrows(RuntimeException.class, () -> SshCommandService.parseHostKeyScan(
                new SshEndpoint("gitea.example.com", 22), output));
    }

    @Test
    void generate_realEd25519PairWithSafeCommentAndRedaction() {
        var pair = new SshCommandService().generateKeyPair("gitbot\ncomment");
        assertTrue(pair.privateKey().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertTrue(pair.publicKey().startsWith("ssh-ed25519 "));
        assertTrue(pair.publicKey().endsWith("gitbot comment"));
        assertFalse(pair.toString().contains(pair.privateKey()));
    }

    @Test
    void cleanup_deletesBothKeysAndDirectory() throws Exception {
        Path keys = Files.createDirectory(directory.resolve("keys"));
        Files.writeString(keys.resolve("id_ed25519"), "private");
        Files.writeString(keys.resolve("id_ed25519.pub"), "public");
        SshCommandService.deleteKeyFiles(keys);
        assertFalse(Files.exists(keys));
    }

    @Test
    void cleanup_failureIsNotSilentlyIgnored() throws Exception {
        Path keys = Files.createDirectory(directory.resolve("keys"));
        Files.createDirectory(keys.resolve("id_ed25519"));
        Files.writeString(keys.resolve("id_ed25519/child"), "blocked");
        Files.writeString(keys.resolve("id_ed25519.pub"), "public");
        assertThrows(IllegalStateException.class, () -> SshCommandService.deleteKeyFiles(keys));
        assertFalse(Files.exists(keys.resolve("id_ed25519.pub")));
    }
}
