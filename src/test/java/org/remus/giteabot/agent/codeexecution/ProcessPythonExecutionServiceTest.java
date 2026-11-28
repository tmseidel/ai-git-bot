package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandbox end to end, against a real interpreter: the argv, the bridge socket, the limits and the
 * teardown proved to work together rather than in isolation. Skipped where python3 is absent, which is
 * the only environment-dependent part of this suite.
 */
class ProcessPythonExecutionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean pythonAvailable;

    @BeforeAll
    static void detectPython() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            pythonAvailable = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            pythonAvailable = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pythonAvailable = false;
        }
    }

    private static ProcessPythonExecutionService service(Consumer<AgentConfigProperties> tweak) {
        AgentConfigProperties config = new AgentConfigProperties();
        tweak.accept(config);
        return new ProcessPythonExecutionService(config);
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static List<ToolDescriptor> advertised(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> new ToolDescriptor(name, "Read a file.",
                        JSON.createObjectNode().put("type", "object")))
                .toList();
    }

    private static ToolResult ok(String output) {
        return new ToolResult(true, 0, output, "");
    }

    private static CodeExecutionScope scope(List<ToolDescriptor> available,
                                            PythonToolExecutor executor) {
        return new CodeExecutionScope(available, executor);
    }

    private static PythonToolExecutor returns(ToolResult result) {
        return (tool, arguments) -> result;
    }


    @Test
    void multiByteOutputWithinTheCapIsNotCalledTruncated() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(50))
                        .execute("print(chr(252) * 40)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).isEqualTo("ü".repeat(40) + "\n");
        assertThat(outcome.output()).doesNotContain("truncated");
    }

    @Test
    void truncationKeepsTheCapAndNeverSplitsASurrogatePair() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(5))
                        .execute("print(chr(0x1F600) * 4)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).contains("[output truncated at 5 chars]");
        assertThat(hasLoneSurrogate(outcome.output())).isFalse();
    }

    @Test
    void stdoutBecomesTheResult() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { })
                .execute("print('hello from python')", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.output()).isEqualTo("hello from python\n");
        assertThat(outcome.error()).isEmpty();
    }

    @Test
    void theArgvIsolatesTheInterpreterAndCarriesTheSandboxEnvironment() throws IOException {
        Path workspace = Files.createTempDirectory("execute-code-argv-");
        try {
            ProcessBuilder processBuilder = service(config -> { }).command(workspace, null);

            assertThat(processBuilder.command()).containsExactly("python3", "-I", "-u", "-B",
                    workspace.resolve("bootstrap.py").toString(),
                    workspace.resolve("program.py").toString());
            assertThat(processBuilder.directory()).isEqualTo(workspace.toFile());
            // The environment is scrubbed first, so this also proves the sandbox variables are added
            // after the scrub rather than being cleared by it.
            Map<String, String> environment = processBuilder.environment();
            assertThat(environment).containsKey("AI_GIT_BOT_BRIDGE");
            assertThat(environment.get("AI_GIT_BOT_LIMIT_AS_BYTES")).isEqualTo("268435456");
            assertThat(environment.get("AI_GIT_BOT_LIMIT_NPROC")).isEqualTo("64");
            assertThat(environment.get("TMPDIR")).isEqualTo(workspace.toString());
        } finally {
            Files.deleteIfExists(workspace);
        }
    }

    @Test
    void aConfiguredPoolPutsSudoAndTheSandboxEnvironmentInFrontOfTheInterpreter() throws IOException {
        Path workspace = Files.createTempDirectory("execute-code-argv-");
        try {
            ProcessBuilder processBuilder = service(config -> config.getCodeExecution()
                    .setSandboxSlots("/etc/execute-code/sandbox-slots"))
                    .command(workspace, new SandboxSlots.Slot("execute-code-10007", 10007L, 10007L));

            // sudo first, and -n so a switch that would have to ask for a password fails the run
            // instead of asking: the program must never start with the service user's identity and
            // drop it afterwards, and the slot is what decides which identity it starts with instead —
            // one per execution, so a concurrent run is a different principal. The variables travel in
            // the argv because sudo rebuilds the environment from its own defaults.
            assertThat(processBuilder.command()).containsExactly(
                    "/usr/bin/sudo", "-n", "-u", "execute-code-10007", "--", "env",
                    "AI_GIT_BOT_BRIDGE=" + workspace.resolve("bridge.sock"),
                    "AI_GIT_BOT_LIMIT_AS_BYTES=268435456",
                    "AI_GIT_BOT_LIMIT_CPU_SECONDS=120",
                    "AI_GIT_BOT_LIMIT_FSIZE_BYTES=10485760",
                    "AI_GIT_BOT_LIMIT_NPROC=64",
                    "TMPDIR=" + workspace,
                    "python3", "-I", "-u", "-B",
                    workspace.resolve("bootstrap.py").toString(),
                    workspace.resolve("program.py").toString());
            assertThat(processBuilder.environment()).doesNotContainKey("AI_GIT_BOT_BRIDGE");
        } finally {
            Files.deleteIfExists(workspace);
        }
    }

    /**
     * A deployment that named a pool has decided the program must not run as the service user, so a
     * pool that cannot even be read is a failed run — not a quiet fallback, and not a program that
     * runs with the service user's reach.
     */
    @Test
    void aPoolThatCannotBeReadFailsTheRunInsteadOfRunningAsTheServiceUser() {
        PythonExecutionOutcome outcome = service(config -> config.getCodeExecution()
                .setSandboxSlots("/etc/execute-code/no-such-sandbox-slots"))
                .execute("print('hello')", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("sandbox failure:");
        assertThat(outcome.output()).doesNotContain("hello");
    }

    /**
     * The workspace is handed to a slot's group by the JVM itself, and chgrp is a membership check: a
     * pool naming a group this JVM was never added to must fail the run. Accepting it would leave
     * either a program that cannot read its own bootstrap or — the case that matters — one that ran
     * anyway. Skipped as root, where group membership is not checked.
     */
    @Test
    void aSlotTheJvmCannotHandTheWorkspaceToFailsTheRun() throws IOException {
        Assumptions.assumeTrue(serviceUserUid() != 0, "running as root: chgrp is not checked");
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            // 12345 is nobody's group: the deployment forgot to add the service user to the pool.
            Path pool = SandboxTestSupport.poolFile(directory, "execute-code-12345", 12345, 12345);
            Path sudo = SandboxTestSupport.fakeSudo(directory, directory.resolve("sudo.log"));
            ProcessPythonExecutionService sandboxed = service(config -> {
                config.getCodeExecution().setSandboxSlots(pool.toString());
                config.getCodeExecution().setSudoBinary(sudo.toString());
            });

            PythonExecutionOutcome outcome = sandboxed.execute("print('hello')",
                    scope(List.of(), returns(ok(""))));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.error()).contains("sandbox failure:");
            assertThat(outcome.output()).doesNotContain("hello");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /**
     * What the service does around the switch, with a pool naming a slot this test run can actually be
     * given: the workspace carries the slot's group and the modes the program needs, the socket is
     * writable by that group and by nobody else, and the tool surface is reachable.
     */
    @Test
    void aSandboxedRunOpensItsWorkspaceToItsOwnSlot() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            long slot = SandboxTestSupport.slotGid(directory);
            Path pool = SandboxTestSupport.poolFile(directory, "execute-code-test", slot, slot);
            Path sudo = SandboxTestSupport.fakeSudo(directory, directory.resolve("sudo.log"));
            ProcessPythonExecutionService sandboxed = service(config -> {
                config.getCodeExecution().setSandboxSlots(pool.toString());
                config.getCodeExecution().setSudoBinary(sudo.toString());
            });

            PythonExecutionOutcome outcome = sandboxed.execute("""
                    import os
                    import stat

                    print("dir", oct(stat.S_IMODE(os.stat(".").st_mode)), os.stat(".").st_gid)
                    print("socket", oct(stat.S_IMODE(os.stat("bridge.sock").st_mode)))
                    print("tool", tools.call("cat", {"path": "README.md"})["output"])
                    """, scope(advertised("cat"), returns(ok("FILE-CONTENT"))));

            assertThat(outcome.error()).isEmpty();
            assertThat(outcome.output())
                    .contains("dir 0o770 " + slot)
                    .contains("socket 0o660")
                    .contains("tool FILE-CONTENT");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /**
     * A slot goes back when the run ends, or the second run would be refused on a pool that has
     * nothing in flight. One slot, two runs.
     */
    @Test
    void aSlotIsHandedOutAgainAfterARun() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path directory = Files.createTempDirectory("execute-code-slots-");
        try {
            long slot = SandboxTestSupport.slotGid(directory);
            Path pool = SandboxTestSupport.poolFile(directory, "execute-code-test", slot, slot);
            Path sudo = SandboxTestSupport.fakeSudo(directory, directory.resolve("sudo.log"));
            ProcessPythonExecutionService sandboxed = service(config -> {
                config.getCodeExecution().setSandboxSlots(pool.toString());
                config.getCodeExecution().setSudoBinary(sudo.toString());
            });

            assertThat(sandboxed.execute("print('first')", scope(List.of(), returns(ok("")))).output())
                    .isEqualTo("first\n");
            assertThat(sandboxed.execute("print('second')", scope(List.of(), returns(ok("")))).output())
                    .isEqualTo("second\n");
        } finally {
            SandboxTestSupport.delete(directory);
        }
    }

    /**
     * The hardened path end to end, against a real pool and the deployment's sudo. It needs a
     * deployment that provisioned one — the shipped image does — so it is skipped unless a system
     * property names that pool:
     *
     * <pre>
     * mvn -Dtest=ProcessPythonExecutionServiceTest test \
     *     -Dsandbox.test.slots=/etc/execute-code/sandbox-slots
     * </pre>
     *
     * <p>The uid alone is not the property under test. A program that runs as another user but can
     * still read this JVM's start-time environment has gained nothing; one that can no longer reach
     * the bridge socket has lost the tool surface; and one that can walk into the next run's workspace
     * and talk to <em>its</em> bridge socket is not isolated at all — which is the failure a single
     * sandbox uid permits and a slot per execution does not. All of it is asserted here.</p>
     */
    @Test
    void aSandboxedProgramReadsNoServiceEnvironmentAndNoOtherRun() throws IOException {
        Path pool = sandboxSlots();
        Assumptions.assumeTrue(pool != null, "no sandbox pool is provisioned for this run");

        List<Long> poolGids = poolGids(pool);
        Assumptions.assumeTrue(poolGids.size() >= 2, "a pool of one cannot show that runs are strangers");
        // The last slot, because the service hands out the first free one and the decoy has to be a
        // workspace the running program does not own.
        Path decoy = decoyWorkspace(poolGids.getLast());

        String program = """
                import glob
                import os
                import subprocess
                import sys

                print("uid", os.getuid())
                try:
                    raw = open("/proc/" + str(os.getppid()) + "/environ", "rb").read()
                    print("READ", len(raw))
                except OSError as error:
                    print("refused", type(error).__name__)

                other = glob.glob("/tmp/execute-code-decoy-*")[0]
                try:
                    open(other + "/program.py").read()
                    print("DECOY REACHED")
                except OSError as error:
                    print("decoy refused", type(error).__name__)
                probe = subprocess.run([sys.executable, "-I", "-c",
                        "import socket, sys; s = socket.socket(socket.AF_UNIX); s.connect(sys.argv[1])",
                        other + "/bridge.sock"], capture_output=True)
                print("socket", probe.stderr.decode().strip().splitlines()[-1] if probe.returncode else "REACHED")

                print("tool", tools.call("cat", {"path": "README.md"})["output"])
                """;

        PythonExecutionOutcome outcome;
        try {
            outcome = service(config -> {
                config.getCodeExecution().setSandboxSlots(pool.toString());
                config.getCodeExecution().setSudoBinary(sandboxSudo());
            }).execute(program, scope(advertised("cat"), (tool, arguments) -> ok("FILE-CONTENT")));
        } finally {
            SandboxTestSupport.delete(decoy);
        }

        assertThat(outcome.error()).isEmpty();
        assertThat(outcome.output()).doesNotContain("uid " + serviceUserUid() + "\n");
        assertThat(outcome.output()).contains("refused PermissionError");
        assertThat(outcome.output()).doesNotContain("READ ");
        assertThat(outcome.output()).contains("decoy refused PermissionError");
        assertThat(outcome.output()).doesNotContain("DECOY REACHED");
        assertThat(outcome.output()).contains("socket PermissionError");
        assertThat(outcome.output()).doesNotContain("socket REACHED");
        assertThat(outcome.output()).contains("tool FILE-CONTENT");
    }

    /**
     * The wall-clock timeout has to work across the identity boundary, or it stops being a timeout:
     * the JVM can no longer signal the program it started, and the process-group kill cannot reach a
     * program that has a session of its own, so the run is stopped the way sudo allows — as the slot,
     * by uid. Without that, the endless program below would run on to its {@code RLIMIT_CPU}.
     */
    @Test
    void aTimedOutSandboxedProgramIsStillStopped() {
        Path pool = sandboxSlots();
        Assumptions.assumeTrue(pool != null, "no sandbox pool is provisioned for this run");

        PythonExecutionOutcome outcome = service(config -> {
            config.getCodeExecution().setSandboxSlots(pool.toString());
            config.getCodeExecution().setSudoBinary(sandboxSudo());
            config.getValidation().setToolTimeoutSeconds(2);
        }).execute("print('before the loop')\nwhile True:\n    pass\n", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(124);
        assertThat(outcome.error()).contains("timed out after 2s");
        assertThat(outcome.output()).contains("before the loop");
    }

    /** The provisioned pool, or {@code null} when this run was not told to use one. */
    private static Path sandboxSlots() {
        String pool = System.getProperty("sandbox.test.slots", "");
        if (pool.isBlank() || !Files.isReadable(Path.of(pool))) {
            return null;
        }
        return Path.of(pool);
    }

    /** The sudo the deployment switches with. Overridable only to point the test elsewhere. */
    private static String sandboxSudo() {
        return System.getProperty("sandbox.test.sudo", "/usr/bin/sudo");
    }

    private static int serviceUserUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    /** The groups the pool hands out: what a decoy workspace has to carry to be another slot's. */
    private static List<Long> poolGids(Path pool) throws IOException {
        return Files.readAllLines(pool, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> Long.parseLong(line.split("\\s+")[2]))
                .toList();
    }

    /**
     * What a concurrent run's workspace looks like: the service user owns it, one slot's group is on
     * it, and it holds the two things a shared sandbox uid would hand over — a program to read and a
     * bridge socket to talk to. The test builds it because the service deletes its own workspace when
     * a run ends, and because this one can be aimed at a slot the running program does not have.
     */
    private static Path decoyWorkspace(long gid) throws IOException {
        Path decoy = Files.createTempDirectory("execute-code-decoy-");
        SandboxTestSupport.openToGroup(decoy, gid, "rwxrwx---");
        Path program = decoy.resolve("program.py");
        Files.writeString(program, "print('the other run')\n", StandardCharsets.UTF_8);
        SandboxTestSupport.openToGroup(program, gid, "rw-r-----");
        Path socket = decoy.resolve("bridge.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            SandboxTestSupport.openToGroup(socket, gid, "rw-rw----");
        }
        return decoy;
    }

    @Test
    void aProgramCanCallAToolThroughTheBridge() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                result = ai_git_bot.tools.call("cat", {"path": "README.md"})
                print("tool said: " + result["output"])
                """;
        AtomicInteger relayed = new AtomicInteger();

        PythonExecutionOutcome outcome = service(config -> { }).execute(program,
                scope(advertised("cat"), (tool, arguments) -> {
                    relayed.incrementAndGet();
                    return ok("FILE-CONTENT");
                }));

        assertThat(outcome.error()).isEmpty();
        assertThat(outcome.output()).isEqualTo("tool said: FILE-CONTENT\n");
        assertThat(relayed).hasValue(1);
    }

    /**
     * The tool description tells the model to call {@code tools.call(...)} — the program's own
     * namespace carries that name, so no import is needed. A program that writes it and gets a
     * NameError loses the round and reads as "execute-code is broken".
     */
    @Test
    void theToolSurfaceIsBoundWithoutAnImport() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                result = tools.call("cat", {"path": "README.md", "startLine": None, "endLine": None})
                print("tool said: " + result["output"])
                """;
        AtomicInteger relayed = new AtomicInteger();

        PythonExecutionOutcome outcome = service(config -> { }).execute(program,
                scope(advertised("cat"), (tool, arguments) -> {
                    relayed.incrementAndGet();
                    return ok("FILE-CONTENT");
                }));

        assertThat(outcome.error()).isEmpty();
        assertThat(outcome.output()).isEqualTo("tool said: FILE-CONTENT\n");
        assertThat(relayed).hasValue(1);
    }

    @Test
    void aRefusedToolIsReadableFromTheProgramWithoutAnException() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                result = ai_git_bot.tools.call("write-file", {"path": "x"})
                print("success=%s error=%s" % (result["success"], result["error"]))
                """;

        PythonExecutionOutcome outcome = service(config -> { }).execute(program,
                scope(advertised("write-file"),
                        returns(new ToolResult(false, -1, "",
                                "Tool 'write-file' is not enabled for this bot."))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output())
                .isEqualTo("success=False error=Tool 'write-file' is not enabled for this bot.\n");
    }

    @Test
    void theToolListIsReachableFromTheProgram() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        String program = """
                import ai_git_bot

                listed = ai_git_bot.tools.list()
                print("tools=%d first=%s" % (len(listed), listed[0]["name"]))
                """;

        PythonExecutionOutcome outcome = service(config -> { })
                .execute(program, scope(advertised("cat"), returns(ok(""))));

        assertThat(outcome.output()).isEqualTo("tools=1 first=cat\n");
    }

    @Test
    void theNetworkModulesAreRefusedInsideTheProgram() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        // The C modules behind socket/ssl are separate import names, so the guard has to name them too.
        for (String module : List.of("socket", "_socket", "ssl", "_ssl")) {
            PythonExecutionOutcome outcome = service(config -> { })
                    .execute("import " + module + "\n", scope(List.of(), returns(ok(""))));

            assertThat(outcome.success()).as(module).isFalse();
            assertThat(outcome.output()).as(module).contains("is not available inside execute-code");
        }
    }

    @Test
    void anEndlessProgramIsStoppedByTheTimeout() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome =
                service(config -> config.getValidation().setToolTimeoutSeconds(2))
                .execute("print('before the loop')\nwhile True:\n    pass\n",
                        scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(124);
        assertThat(outcome.error()).contains("timed out after 2s");
        // Proves the output was streamed, not left in a pipe buffer to be lost on the kill.
        assertThat(outcome.output()).contains("before the loop");
    }

    @Test
    void anOversizedOutputIsTruncatedAndMarked() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(1024))
                        .execute("print('x' * 50000)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output()).contains("[output truncated at 1024 chars]");
        assertThat(outcome.output().length()).isLessThanOrEqualTo(1024 + 40);
    }

    /**
     * The contract the model depends on: everything the program prints is the result, in order.
     * The bridge has its own channel, so a program may print whatever it likes.
     */
    @Test
    void everythingTheProgramPrintsIsTheResult() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { }).execute("""
                print("CHARS", 11)
                print("LINES", 1)
                """, scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output()).isEqualTo("CHARS 11\nLINES 1\n");
    }

    /**
     * A program that fails is still a program that printed. Dropping the output would leave an
     * empty result, which reads as a program that chose to say nothing.
     */
    @Test
    void theLinesPrintedBeforeAFailureStillReachTheModel() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { }).execute("""
                print("got this far")
                raise SystemExit("boom")
                """, scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.output()).contains("got this far");
        assertThat(outcome.error()).contains("code 1");
        // What the model actually reads, not only what the sandbox returned.
        assertThat(new ToolResult(outcome.success(), outcome.exitCode(),
                outcome.output(), outcome.error()).formatForAi())
                .contains("got this far");
    }

    /**
     * The program's working directory is its own scratch space, not the checkout: a repository read
     * goes through the tool surface, where the bot's whitelist decides what exists — which is why the
     * tool description spells out {@code tools.call("cat", ...)}. A program could still open an
     * absolute path if it knew one (layer 1 confines no filesystem); the working directory simply
     * never hands it one.
     */
    @Test
    void theProgramsWorkingDirectoryHoldsNothingButTheSandbox() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        PythonExecutionOutcome outcome = service(config -> { })
                .execute("import os\nprint(sorted(os.listdir('.')))",
                        scope(List.of(), returns(ok(""))));

        assertThat(outcome.output())
                .contains("ai_git_bot.py")
                .contains("bootstrap.py")
                .contains("program.py");
    }

    @Test
    void aProgramOverTheSizeLimitIsRefusedWithoutSpawningAnything() {
        PythonExecutionOutcome outcome =
                service(config -> config.getCodeExecution().setMaxCodeSize(DataSize.ofBytes(10)))
                        .execute("print('far too long for ten bytes')",
                                scope(List.of(), returns(ok(""))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("over the limit of 10 bytes");
    }

    /**
     * The capture is bounded in bytes, so multi-byte output can be cut before the character cap is
     * reached. Without a marker the caller reads a shortened result as a complete one.
     */
    @Test
    void multiByteOutputCutByTheByteBudgetIsMarkedTruncated() {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");

        // 2000 two-byte characters are 4000 bytes: past the byte budget (2000 + 1024), while the
        // character count stays under the 2000-character cap, so only the byte test can see the cut.
        PythonExecutionOutcome outcome =
                service(config -> config.getBudget().setMaxToolResultChars(2000))
                        .execute("print(chr(0xFC) * 2000)", scope(List.of(), returns(ok(""))));

        assertThat(outcome.output()).contains("[output truncated at 3024 bytes]");
    }
}
