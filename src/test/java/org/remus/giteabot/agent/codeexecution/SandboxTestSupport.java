package org.remus.giteabot.agent.codeexecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The pieces the code-execution tests need next to a provisioned sandbox: a pool file, a stand-in for
 * sudo, and the cleanup for what they leave behind.
 */
final class SandboxTestSupport {

    private SandboxTestSupport() {
    }

    /**
     * A pool file in the format the installer writes, {@code name uid gid}. The tests give the slot
     * this run's own group, because handing a workspace over is a chgrp and chgrp works only for a
     * group the JVM is in.
     */
    static Path poolFile(Path directory, String name, long uid, long gid) throws IOException {
        Path pool = directory.resolve("sandbox-slots");
        Files.writeString(pool, name + " " + uid + " " + gid + "\n", StandardCharsets.UTF_8);
        return pool;
    }

    /** A stand-in for sudo: records every invocation and execs everything but the clearing command. */
    static Path fakeSudo(Path directory, Path log) throws IOException {
        return fakeSudo(directory, log, null);
    }

    /**
     * A stand-in for sudo. It records every invocation, and for anything but the pool's clearing
     * command it steps out of the way and execs what it was handed. That exercises everything the
     * service does around the switch — the pool, the workspace handover, the argv, the release, the
     * cleanup — without a provisioned sandbox identity. The switch itself is sudo's job and is
     * deliberately not faked: no test here may pass by pretending to isolate something.
     *
     * <p>The clearing command is answered without being run, because it is
     * {@code kill -KILL -- -1}: as this test run's own uid that is every process the JVM can signal,
     * itself included.</p>
     *
     * @param refusal when set, every invocation prints this and fails the way a missing sudo rule
     *        does — for the tests that check a sandbox which cannot switch fails the run
     */
    static Path fakeSudo(Path directory, Path log, String refusal) throws IOException {
        Path script = directory.resolve("sudo");
        String body = refusal == null
                ? """
                while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do shift; done
                shift
                if [ "$1" = "kill" ]; then
                    exit 0
                fi
                exec "$@"
                """
                : "echo \"" + refusal + "\" >&2\nexit 1\n";
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "LOGFILE"
                BODY""".replace("LOGFILE", log.toString()).replace("BODY", body),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /** What the stand-in was called with, in order. */
    static List<String> invocations(Path log) throws IOException {
        if (!Files.exists(log)) {
            return List.of();
        }
        return Files.readAllLines(log, StandardCharsets.UTF_8);
    }

    /** The group a directory of this test run belongs to: a slot the JVM can actually hand over. */
    static long ownGid(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:gid");
    }

    /** {@code nogroup}: an unprivileged group, for runs whose own group is root's. */
    static final long UNPRIVILEGED_GID = 65534;

    /**
     * The group to give a test slot: this run's own group, so the JVM can chgrp a workspace to it —
     * except when that is group 0. A pool naming gid 0 is refused as a privileged identity, and a
     * build running as root (a CI container) would trip that check instead of testing anything. Root
     * may chgrp to any group, so an unprivileged stand-in works there just as well.
     */
    static long slotGid(Path path) throws IOException {
        long gid = ownGid(path);
        return gid == 0 ? UNPRIVILEGED_GID : gid;
    }

    static void openToGroup(Path path, long gid, String mode) throws IOException {
        Files.setAttribute(path, "unix:gid", (int) gid);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    static void delete(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A socket or a file another process still holds; the directory is not precious.
                }
            });
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
