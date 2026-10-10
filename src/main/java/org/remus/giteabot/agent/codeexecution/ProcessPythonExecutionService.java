package org.remus.giteabot.agent.codeexecution;

import lombok.extern.slf4j.Slf4j;

import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
import org.remus.giteabot.util.TextSupport;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The Layer 1 sandbox: a restricted local subprocess.
 *
 * <p>{@code python3 -I -u -B bootstrap.py program.py}, in a fresh 0700 temp directory that is also
 * its cwd and {@code TMPDIR}, with a scrubbed environment, rlimits applied by the bootstrap, and a
 * wall-clock timeout enforced here. The container is the real isolation boundary
 * ({@code doc/development-archive/sandbox-approach.md}, ADR-1) — nothing in this class claims
 * otherwise.</p>
 *
 * <p>Where the deployment names a sandbox pool, the program does not run as the service user at
 * all: {@link #command} has sudo switch to one slot of the pool before the interpreter starts, and
 * the temp directory, the files in it and the bridge socket are opened to that slot's group. The
 * program then reads neither the service user's files nor this JVM's start-time environment
 * ({@code /proc/<jvm-pid>/environ} is granted to same-uid readers only). The identity is per
 * execution rather than one sandbox account for all of them, and that is not a detail: with one
 * shared uid a program can list {@code /tmp/execute-code-*}, read a concurrent run's source, connect
 * to its bridge socket — served with that run's bot whitelist — and kill its process.
 * {@link SandboxSlots} holds the pool; {@code docker/install-execute-code-sandbox.sh} provisions it
 * along with the sudo rule that reaches it, and that rule can name nothing but those slots. Nothing
 * here needs a capability of the JVM's own, on java or on any other binary: switching to the slot and
 * signalling what it owns are both things the rule already allows.</p>
 *
 * <p>stdout carries the program's output and nothing else. Tool calls travel over an {@code AF_UNIX}
 * socket in the temp directory (permissions are filesystem permissions: no port, nothing to
 * authenticate) and are served by a second thread while this one drains the process's output —
 * draining only after the conversation finished would deadlock as soon as a program printed more
 * than a pipe buffer before calling a tool.</p>
 *
 * <p>The sandbox decides nothing about which tools exist or whether the run is allowed: it relays a
 * name and its arguments to the surface's own executor ({@link PythonToolExecutor}) and hands the
 * answer back. Whether a bot may run a program at all is decided before this service is reached: the
 * tool is opt-in per bot, and {@link org.remus.giteabot.agent.tools.AgentToolRouter} refuses it when
 * the bot's configuration does not select it.</p>
 *
 * <p>Two deviations from the plan's wording, both deliberate: the serving thread is a platform daemon
 * thread rather than a virtual one (there is one per execution, so the scheduler buys nothing), and
 * the process's output is drained by {@link ProcessSupport} rather than by a thread of our own — it
 * already bounds the captured bytes and escalates a timeout to a process-group kill.</p>
 */
@Slf4j
@Service
public class ProcessPythonExecutionService implements PythonExecutionService {

    private static final String BRIDGE_ENV = "AI_GIT_BOT_BRIDGE";
    private static final String BOOTSTRAP_RESOURCE = "/codeexecution/bootstrap.py";
    private static final String MODULE_RESOURCE = "/codeexecution/ai_git_bot.py";
    private static final String BOOTSTRAP_FILE = "bootstrap.py";
    private static final String MODULE_FILE = "ai_git_bot.py";
    private static final String PROGRAM_FILE = "program.py";
    private static final String SOCKET_FILE = "bridge.sock";
    private static final String BRIDGE_THREAD = "execute-code-bridge";

    /**
     * How long an execution waits for a free identity before the run fails. A slot is held for the
     * length of one execution, so waiting longer than the longest run cannot help; this is long
     * enough for a normal run to finish, and short enough to give back a usable error rather than a
     * stalled tool call when the pool is genuinely too small.
     */
    private static final Duration SANDBOX_SLOT_WAIT = Duration.ofSeconds(30);

    /**
     * Permissions the workspace, the files in it and the bridge socket carry when the program runs
     * as a sandbox account: reachable by the group both accounts share and by nobody else. The group
     * is what makes that possible without giving the JVM a second privilege — it owns the directory
     * either way.
     */
    private static final String SANDBOX_DIRECTORY_MODE = "rwxrwx---";
    private static final String SANDBOX_FILE_MODE = "rw-r-----";
    private static final String SANDBOX_SOCKET_MODE = "rw-rw----";

    /**
     * Captured beyond the configured cap, purely so truncation can be detected: with the cap passed
     * straight through, a result that stopped exactly at the cap would be indistinguishable from one
     * that was cut off.
     */
    private static final int OUTPUT_SLACK_BYTES = 1024;

    /** The conventional timeout exit code, so a caller can tell it from the program's own. */
    private static final int EXIT_TIMEOUT = 124;

    /**
     * How long teardown waits for an in-flight nested call before the workspace is deleted. The
     * tool timeout is too long to hold the agent thread here: the nested call is already bounded by
     * its own timeout ({@code agent.validation.tool-timeout-seconds}, enforced by the executor it
     * reaches), so a call that respects it finishes well inside this window, and one that does not
     * is reported rather than waited on for minutes.
     */
    private static final Duration NESTED_TEARDOWN_WAIT = Duration.ofSeconds(10);

    private final CodeExecutionLimits limits;
    private final SandboxSlots slots;
    private final ObjectMapper json = new ObjectMapper();

    public ProcessPythonExecutionService(AgentConfigProperties config) {
        this.limits = CodeExecutionLimits.from(config);
        this.slots = new SandboxSlots(limits.sandboxSlots(), limits.sudoBinary(), SANDBOX_SLOT_WAIT);
    }

    @Override
    public PythonExecutionOutcome execute(String code, CodeExecutionScope scope) {
        if (code == null || code.isBlank()) {
            return PythonExecutionOutcome.failed(1, "", "no program submitted");
        }
        int codeBytes = code.getBytes(StandardCharsets.UTF_8).length;
        if (codeBytes > limits.maxCodeBytes()) {
            return PythonExecutionOutcome.failed(1, "",
                    "program is " + codeBytes + " bytes, over the limit of "
                            + limits.maxCodeBytes() + " bytes");
        }

        Path workspace = null;
        SandboxSlots.Slot slot = null;
        try {
            if (!limits.sandboxSlots().isBlank()) {
                // An identity of this execution's own (SandboxSlots). A pool with no free slot fails
                // the run: falling back to the service user is the one thing the pool is there to
                // prevent.
                slot = slots.acquire();
                if (slot == null) {
                    return PythonExecutionOutcome.failed(1, "", "sandbox failure: all "
                            + slots.size() + " sandbox slots are in use");
                }
            }
            workspace = Files.createTempDirectory("execute-code-");
            if (slot == null) {
                restrictToOwner(workspace);
            } else {
                openToSandbox(workspace, slot.gid(), SANDBOX_DIRECTORY_MODE);
            }
            writeResource(workspace, BOOTSTRAP_FILE, BOOTSTRAP_RESOURCE);
            writeResource(workspace, MODULE_FILE, MODULE_RESOURCE);
            Files.writeString(workspace.resolve(PROGRAM_FILE), code, StandardCharsets.UTF_8);
            if (slot != null) {
                // The program has to read all three and the JVM created them, so their group is
                // settled here rather than by a umask.
                for (String file : List.of(BOOTSTRAP_FILE, MODULE_FILE, PROGRAM_FILE)) {
                    openToSandbox(workspace.resolve(file), slot.gid(), SANDBOX_FILE_MODE);
                }
            }

            Path socketPath = workspace.resolve(SOCKET_FILE);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socketPath));
                if (slot != null) {
                    // Connecting to a unix socket needs write permission on it, so the socket is the
                    // one path in the workspace the group may write.
                    openToSandbox(socketPath, slot.gid(), SANDBOX_SOCKET_MODE);
                }
                PythonToolBridge bridge = new PythonToolBridge(scope.available(), scope.executor(),
                        limits, json);
                Thread serving = startServing(server, bridge);
                ProcessSupport.CommandResult result;
                try {
                    result = ProcessSupport.run(command(workspace, slot),
                            limits.timeout().toSeconds(), TimeUnit.SECONDS,
                            limits.maxResultChars() + OUTPUT_SLACK_BYTES,
                            cleanupOf(slot));
                } finally {
                    serving.interrupt();
                }
                awaitServing(serving);
                return outcome(result, bridge);
            }
        } catch (IOException e) {
            return PythonExecutionOutcome.failed(1, "", "sandbox failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PythonExecutionOutcome.failed(1, "", "execution interrupted");
        } finally {
            if (slot != null) {
                // The program created its temp files and any directories as the slot, and the JVM —
                // which owns the workspace but only holds the slot's group — cannot traverse a
                // directory the program made 0700, let alone delete inside it. Clear the contents as
                // the slot first, then delete what the JVM still owns. Without this the workspace
                // (and everything under it) leaks silently on every run.
                slots.delete(slot, workspace);
            }
            deleteRecursively(workspace);
            slots.release(slot);
        }
    }

    /**
     * The argv. Package-private so a test can pin it: {@code -I} isolates the interpreter from the
     * environment, the user site directory and the script's path entry, {@code -u} keeps the
     * program's output arriving instead of sitting in a pipe buffer (a timeout would otherwise lose
     * the lines that explain what it was doing), {@code -B} keeps the sandbox directory free of
     * bytecode.
     *
     * @param slot the pool identity this execution runs as, or {@code null} for layer 1 only. A slot
     *        puts sudo in front of the interpreter — {@code sudo -n -u <slot> -- python3 …} — which
     *        switches uid and gid before the interpreter starts, so the program never holds the
     *        service user's identity, not even for the instant between fork and exec. sudo is the only
     *        switch that can be restricted to named targets; the rule the installer writes allows the
     *        slots and nothing else ({@code docker/install-execute-code-sandbox.sh}). The slot's
     *        primary gid is the one group the workspace is opened to.
     */
    ProcessBuilder command(Path workspace, SandboxSlots.Slot slot) {
        Map<String, String> environment = sandboxEnvironment(workspace);
        List<String> argv = new ArrayList<>();
        if (slot != null) {
            argv.add(limits.sudoBinary());
            // -n: never ask for a password. A switch that would need one is a provisioning error, and
            // the answer to it is a failed run, not a prompt on a socket nobody is reading.
            argv.add("-n");
            argv.add("-u");
            argv.add(slot.name());
            argv.add("--");
            // sudo rebuilds the environment from its own defaults, so the variables below cannot be
            // handed over the way layer 1 hands them over. None of them is a secret — a path and four
            // limits — so the argv is a fine carrier, and `env` execs the interpreter itself.
            argv.add("env");
            for (Map.Entry<String, String> variable : environment.entrySet()) {
                argv.add(variable.getKey() + "=" + variable.getValue());
            }
        }
        argv.add(limits.pythonBinary());
        argv.add("-I");
        argv.add("-u");
        argv.add("-B");
        argv.add(workspace.resolve(BOOTSTRAP_FILE).toString());
        argv.add(workspace.resolve(PROGRAM_FILE).toString());
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.directory(workspace.toFile());
        // stdout and stderr are one stream: the program's output is the tool result, and a traceback
        // belongs in it rather than in a log nobody reads.
        processBuilder.redirectErrorStream(true);

        // The environment is replaced first; the sandbox's own variables are added after, or the
        // scrub would remove them. Layer 1 adds them here; a sandboxed run already carries them in
        // the argv, because sudo does not pass the caller's environment on.
        ProcessSupport.scrubEnvironment(processBuilder);
        if (slot == null) {
            processBuilder.environment().putAll(environment);
        }
        return processBuilder;
    }

    /**
     * What the program needs from the environment: where to find the bridge, the limits the bootstrap
     * applies to itself, and where its temporary files go. Ordered, so the argv a sandboxed run builds
     * from it is the same on every execution.
     */
    private Map<String, String> sandboxEnvironment(Path workspace) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(BRIDGE_ENV, workspace.resolve(SOCKET_FILE).toString());
        environment.put("AI_GIT_BOT_LIMIT_AS_BYTES", Long.toString(limits.maxMemoryBytes()));
        environment.put("AI_GIT_BOT_LIMIT_CPU_SECONDS", Integer.toString(limits.cpuSeconds()));
        environment.put("AI_GIT_BOT_LIMIT_FSIZE_BYTES", Long.toString(limits.maxFileSizeBytes()));
        environment.put("AI_GIT_BOT_LIMIT_NPROC", Integer.toString(limits.maxProcesses()));
        // Temp files land in the directory that is deleted with the execution, not in /tmp.
        environment.put("TMPDIR", workspace.toString());
        return environment;
    }

    /**
     * What the timeout runs in place of the process-group kill, for a sandboxed execution: the JVM
     * cannot signal a process of another uid, so the program is stopped as the identity that owns it
     * ({@link SandboxSlots#stop}). {@code null} for layer 1, which keeps the default kill.
     */
    private Runnable cleanupOf(SandboxSlots.Slot slot) {
        return slot == null ? null : () -> slots.stop(slot);
    }

    private Thread startServing(ServerSocketChannel server, PythonToolBridge bridge) {
        Thread serving = new Thread(() -> serve(server, bridge), BRIDGE_THREAD);
        serving.setDaemon(true);
        serving.start();
        return serving;
    }

    /**
     * Accepts one connection at a time and answers frame by frame until the socket is closed
     * (teardown) or the program stops asking. A program that opens a second connection is served
     * after the first closes: sequential by design, since the Python side blocks on every reply.
     */
    private void serve(ServerSocketChannel server, PythonToolBridge bridge) {
        while (!Thread.currentThread().isInterrupted()) {
            try (SocketChannel connection = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(connection),
                                StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(connection),
                                StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    writer.write(bridge.handle(line).toString());
                    writer.write('\n');
                    writer.flush();
                }
            } catch (ClosedChannelException e) {
                // Also covers ClosedByInterruptException and AsynchronousCloseException: the server
                // (or the connection) was closed at teardown, which ends the loop.
                return;
            } catch (IOException e) {
                if (!server.isOpen()) {
                    return;
                }
                // The program died mid-conversation. Its output carries the diagnosis, so this only
                // needs to keep the serving loop alive for a reconnect.
            }
        }
    }

    /**
     * Waits for an in-flight nested call before the workspace it may be reading is deleted.
     *
     * <p>{@code interrupt()} does not abort a call blocked on I/O, so a program that ran out of time
     * can leave one behind. The bound is a short, fixed window ({@link #NESTED_TEARDOWN_WAIT}), not
     * the tool timeout: the nested call is already bounded by its own timeout, so waiting the full
     * tool timeout (300 s by default) to confirm it is stuck only stalls the agent thread. A call
     * that outlives the window is reported instead of waited on, and the workspace is removed
     * underneath it — which is why the warning is not decoration.</p>
     *
     * <p>Nested calls run on {@code execute-code-bridge}, not on the agent's thread. Nothing depends on
     * that difference today — the only {@link ThreadLocal}s in the codebase are the AI audit and retry
     * contexts, and tool dispatch does not read them — but an executor that starts to will need its
     * context handed over here.</p>
     */
    private void awaitServing(Thread serving) {
        long bound = NESTED_TEARDOWN_WAIT.toMillis();
        try {
            serving.join(bound);
            if (serving.isAlive()) {
                log.warn("execute-code: a nested tool call is still running {} ms after the program "
                        + "ended; the workspace is being removed underneath it", bound);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PythonExecutionOutcome outcome(ProcessSupport.CommandResult result, PythonToolBridge bridge) {
        String output = result.output();
        int cap = limits.maxResultChars();
        // Characters, the unit the marker names and every other result cap uses. A byte-based test
        // called multi-byte output truncated when nothing was cut, and substring() at a character
        // offset can split a surrogate pair.
        boolean cutByChars = output.length() > cap;
        if (cutByChars) {
            output = TextSupport.cutAtCodePoint(output, cap);
        }
        // Use actual discarded bytes: UTF-8 boundary trimming can leave the decoded output below
        // the byte cap even though capture was truncated. Exact-cap output is not necessarily cut.
        boolean cutByBytes = result.outputTruncated();
        if (cutByChars) {
            output = output + "\n[output truncated at " + cap + " chars]";
        } else if (cutByBytes) {
            output = output + "\n[output truncated at " + (cap + OUTPUT_SLACK_BYTES) + " bytes]";
        }

        String error = "";
        if (!result.finished()) {
            error = "timed out after " + limits.timeout().toSeconds() + "s";
        } else if (result.exitCode() != 0) {
            error = "program exited with code " + result.exitCode();
        }
        if (bridge.budgetExhausted()) {
            error = error.isEmpty()
                    ? "tool-call limit of " + limits.maxToolCalls() + " reached"
                    : error + "; tool-call limit of " + limits.maxToolCalls() + " reached";
        }
        if (!error.isEmpty()) {
            return PythonExecutionOutcome.failed(
                    result.finished() ? result.exitCode() : EXIT_TIMEOUT, output, error,
                    cutByChars || cutByBytes);
        }
        return PythonExecutionOutcome.success(output, cutByChars || cutByBytes);
    }

    private static void writeResource(Path directory, String fileName, String resource)
            throws IOException {
        try (InputStream content = ProcessPythonExecutionService.class.getResourceAsStream(resource)) {
            if (content == null) {
                throw new IOException("classpath resource " + resource + " is missing");
            }
            Files.copy(content, directory.resolve(fileName));
        }
    }

    /**
     * Opens one path in the workspace to the identity that owns this execution.
     *
     * <p>The slot's gid is the one thing the JVM and the program share (the JVM as a member of the
     * slot's group, the program as its primary group), so this is how the program reaches the
     * bootstrap, its own source, its {@code TMPDIR} and the bridge socket without the directory
     * becoming world-accessible — and it is why the next run's program, which has a slot of its own,
     * cannot reach any of them.</p>
     *
     * <p>Set by number, not by name: the pool file carries the gid, and the group is a number
     * everywhere else in this path too. Fails rather than degrades — a JVM that is not a member of
     * the slot's group cannot chgrp to it, and a program that cannot read its own bootstrap must not
     * be the fallback.</p>
     */
    private static void openToSandbox(Path path, long gid, String mode) throws IOException {
        Files.setAttribute(path, "unix:gid", (int) gid);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem: the directory is still the boundary, permissions are the
            // belt-and-braces part.
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        List<Path> undeleted = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    undeleted.add(path);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // The directory was already gone, or the JVM cannot walk into it — a slot-owned
            // directory the shared group does not reach. Say so: a silent leak is exactly what
            // this method exists to prevent.
            log.warn("execute-code: could not walk {} for deletion: {}", root, e.getMessage());
            return;
        }
        if (!undeleted.isEmpty()) {
            log.warn("execute-code: {} path(s) under {} could not be deleted and will leak: {}",
                    undeleted.size(), root,
                    undeleted.size() <= 5 ? undeleted : undeleted.subList(0, 5));
        }
    }
}
