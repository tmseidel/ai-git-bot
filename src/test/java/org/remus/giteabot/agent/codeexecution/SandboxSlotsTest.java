package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pool of sandbox identities: which slots it hands out, that it refuses to be anything but what
 * the deployment provisioned, and that a slot is cleared — before its first use, and after every run
 * that took it. Every way it can go wrong is a failure rather than a run that quietly falls back to
 * the service user, which is the one outcome the pool exists to prevent.
 */
class SandboxSlotsTest {

    /** Longer than one poll needs to give up, short enough not to slow the suite down. */
    private static final Duration WAIT = Duration.ofMillis(200);

    @Test
    void aSlotIsHandedOutOnceAndComesBackWhenItIsReleased() throws Exception {
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            long gid = SandboxTestSupport.slotGid(directory);
            SandboxSlots slots = slots(directory, List.of("execute-code-10007 " + gid + " " + gid));

            SandboxSlots.Slot slot = slots.acquire();
            assertThat(slot.name()).isEqualTo("execute-code-10007");
            assertThat(slot.gid()).isEqualTo(gid);
            assertThat(slots.size()).isEqualTo(1);
            // One slot, one run: the second acquire waits and reports failure rather than queueing for
            // ever or inventing a second identity for a program to run as.
            assertThat(slots.acquire()).isNull();

            slots.release(slot);
            assertThat(slots.acquire()).isNotNull();
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /**
     * A slot is cleared when the pool is read — that is what makes a uid a dead JVM left behind usable
     * again, and it is also the check that the slot works at all — and again when a run ends.
     */
    @Test
    void everySlotIsClearedWhenThePoolIsReadAndWhenARunEnds() throws Exception {
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            long gid = SandboxTestSupport.slotGid(directory);
            SandboxSlots slots = slots(directory, List.of(
                    "execute-code-10007 " + gid + " " + gid,
                    "execute-code-10008 " + gid + " " + gid));
            Path log = directory.resolve("sudo.log");

            SandboxSlots.Slot slot = slots.acquire();
            assertThat(SandboxTestSupport.invocations(log)).containsExactly(
                    "-n -u execute-code-10007 -- kill -KILL -- -1",
                    "-n -u execute-code-10008 -- kill -KILL -- -1");

            slots.stop(slot);
            assertThat(SandboxTestSupport.invocations(log)).last()
                    .isEqualTo("-n -u execute-code-10007 -- kill -KILL -- -1");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    @Test
    void aPoolThatCannotBeReadFailsInsteadOfRunningAsTheServiceUser() {
        Path missing = Path.of("/no/such/execute-code/sandbox-slots");
        SandboxSlots slots = new SandboxSlots(missing.toString(), "/usr/bin/sudo", WAIT);

        assertThatThrownBy(slots::acquire)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sandbox pool " + missing + " could not be read");
    }

    /**
     * A pool line naming uid 0 would be an entry for root: the sudo rule does not cover it, so it
     * could only ever fail — refused loudly here rather than mid-run.
     */
    @Test
    void aPoolNamingAPrivilegedIdentityIsRefused() throws IOException {
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            SandboxSlots slots = slots(directory, List.of("root 0 0"));

            assertThatThrownBy(slots::acquire)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("names a privileged identity");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /** A slot sudo will not switch to — a missing rule, a password it would have to ask for. */
    @Test
    void aSlotSudoRefusesFailsTheRead() throws IOException {
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            long gid = SandboxTestSupport.slotGid(directory);
            Path pool = SandboxTestSupport.poolFile(directory, "execute-code-10007", gid, gid);
            Path sudo = SandboxTestSupport.fakeSudo(directory, directory.resolve("sudo.log"),
                    "sudo: a password is required");
            SandboxSlots slots = new SandboxSlots(pool.toString(), sudo.toString(), WAIT);

            assertThatThrownBy(slots::acquire)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("sandbox slot execute-code-10007 cannot be used")
                    .hasMessageContaining("a password is required");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /** A pool in its own directory, with a stand-in sudo that records what it was called for. */
    private static SandboxSlots slots(Path directory, List<String> lines) throws IOException {
        Path pool = directory.resolve("sandbox-slots");
        Files.writeString(pool, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Path sudo = SandboxTestSupport.fakeSudo(directory, directory.resolve("sudo.log"));
        return new SandboxSlots(pool.toString(), sudo.toString(), WAIT);
    }
}
