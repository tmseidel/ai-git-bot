package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.codeexecution.CodeExecutionScope;
import org.remus.giteabot.agent.codeexecution.ProcessPythonExecutionService;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The nested half of the router: what a sandboxed program's tool call does.
 *
 * <p>The point of these tests is that a program gets no second dispatch path. A nested call goes
 * through {@code execute} like any other, so the whitelist gates it, the arguments arrive flattened
 * where the executors expect them, and {@code execute-code} itself is out of reach — a program
 * cannot nest sandboxes.</p>
 *
 * <p>{@code execute-code} has to be on the whitelist in these tests, exactly as it does in
 * production: the whitelist is checked before anything else, so a bot that has not selected the
 * tool cannot reach the sandbox at all.</p>
 */
class AgentToolRouterExecuteCodeTest {

    private static final AgentConfigProperties CONFIG = new AgentConfigProperties();

    private static ProcessPythonExecutionService optedInSandbox() {
        AgentConfigProperties config = new AgentConfigProperties();
        return new ProcessPythonExecutionService(config);
    }

    /** Detected once: this is the only test here that needs a real interpreter. */
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

    /** A stand-in for the sandbox: it makes one nested call and hands the answer straight back. */
    private static final class Program implements PythonExecutionService {

        private final String tool;
        private final String arguments;
        private ToolResult nested;
        private List<ToolDescriptor> advertised;

        Program(String tool, String arguments) {
            this.tool = tool;
            this.arguments = arguments;
        }

        @Override
        public PythonExecutionOutcome execute(String code, CodeExecutionScope scope) {
            this.advertised = scope.available();
            this.nested = scope.executor().call(tool, node(arguments));
            return new PythonExecutionOutcome(nested.success(), nested.exitCode(), nested.output(),
                    nested.error());
        }
    }

    private static JsonNode node(String json) {
        return AgentJackson.mapper().readTree(json);
    }

    private static ToolCallContext context(Path workspace, String... args) {
        return new ToolCallContext("owner", "repo", 1L, Workspace.unmanaged(workspace),
                ImplementationPlan.ToolRequest.builder()
                        .id("tool-1")
                        .tool("execute-code")
                        .args(List.of(args))
                        .build(),
                null);
    }

    private static AgentToolRouter router(ToolExecutionService tools, Set<String> allowed,
                                         PythonExecutionService sandbox) {
        return new AgentToolRouter(tools, new ToolCatalog(CONFIG), mock(McpOrchestrationService.class),
                null, McpToolCatalog.empty(), mock(RepositoryApiClient.class), allowed, sandbox);
    }

    private static Set<String> botWithExecuteCode() {
        return Set.of("execute-code", "cat");
    }

    @Test
    void aProgramsToolCallReachesTheSurfaceDispatch() throws IOException {
        Path workspace = Files.createTempDirectory("router-nested");
        Files.writeString(workspace.resolve("Main.java"), "class Main {}\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG));
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        ToolResult result = router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        // The file's contents came back, so cat received the path flattened out of the JSON.
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("class Main {}");
        // What the program sees is what the model would have seen.
        assertThat(program.nested.output()).contains("class Main {}");
    }

    @Test
    void aProgramSeesTheSurfaceAdvertisedTools() throws IOException {
        Path workspace = Files.createTempDirectory("router-advert");
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        router(mock(ToolExecutionService.class), botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.advertised).extracting("name").contains("cat");
    }

    @Test
    void aProgramCannotNestSandboxes() throws IOException {
        Path workspace = Files.createTempDirectory("router-recursion");
        ToolExecutionService tools = mock(ToolExecutionService.class);
        Program program = new Program("execute-code", "{\"code\": \"print(2)\"}");

        router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.nested.success()).isFalse();
        assertThat(program.nested.error()).contains("cannot be called from a program");
        verifyNoInteractions(tools);
    }

    @Test
    void theBotWhitelistGatesProgramsToo() throws IOException {
        Path workspace = Files.createTempDirectory("router-whitelist");
        ToolExecutionService tools = mock(ToolExecutionService.class);
        Program program = new Program("rg", "{\"pattern\": \"Main\"}");

        router(tools, botWithExecuteCode(), program)
                .execute(AgentToolRouter.Mode.CODING, context(workspace, "print(1)"));

        assertThat(program.nested.success()).isFalse();
        verifyNoInteractions(tools);
    }

    @Test
    void aDeploymentWithoutASandboxSaysSo() throws IOException {
        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(), null)
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-nosandbox"), "print(1)"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available in this deployment");
    }

    @Test
    void theProgramIsTheFirstArgument() throws IOException {
        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(),
                new Program("cat", "{\"path\": \"Main.java\"}"))
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-noarg")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("\"code\"");
    }

    /**
     * A model call that names the wrong property ({@code program} instead of {@code code}) must be
     * refused, not run. The mapping leaves no argument for a declared tool whose shape it does not
     * recognise — the raw object used to fall through and evaluate as a silent, successful no-op.
     */
    @Test
    void aCallThatNamesTheWrongPropertyIsRefusedInsteadOfRun() throws IOException {
        ToolCatalog catalog = new ToolCatalog(CONFIG);
        ToolCallContext context = new ToolCallContext("owner", "repo", 1L,
                Workspace.unmanaged(Files.createTempDirectory("router-wrong-arg")),
                ImplementationPlan.ToolRequest.builder()
                        .id("tool-1")
                        .tool("execute-code")
                        .args(ToolArguments.toPositional("execute-code",
                                node("{\"program\": \"print('should not run')\"}"),
                                catalog.schemaOf("execute-code").orElse(null)))
                        .build(),
                null);

        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(),
                optedInSandbox())
                .execute(AgentToolRouter.Mode.CODING, context);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("\"code\"");
    }

    @Test
    void aBotWithoutTheToolNeverReachesTheSandbox() throws IOException {
        Program program = new Program("cat", "{\"path\": \"Main.java\"}");

        ToolResult result = router(mock(ToolExecutionService.class), Set.of("cat"), program)
                .execute(AgentToolRouter.Mode.CODING,
                        context(Files.createTempDirectory("router-unselected"), "print(1)"));

        assertThat(result.success()).isFalse();
        assertThat(program.advertised).isNull();
    }

    @Test
    void aRealProgramRunsThroughTheDispatchAndPrintsItsNestedCall() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path workspace = Files.createTempDirectory("router-real-python");
        Files.writeString(workspace.resolve("Main.java"), "class Main {}\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG));
        String program = """
                import ai_git_bot

                result = ai_git_bot.tools.call("cat", {"path": "Main.java"})
                print(result["output"])
                """;

        ToolResult result = router(tools, botWithExecuteCode(),
                optedInSandbox())
                .execute(AgentToolRouter.Mode.CODING, context(workspace, program));

        // The file's text made it: python -> bridge -> router -> the real cat -> back out of stdout.
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("class Main {}");
    }

    /**
     * A program submitted the way the model submits it, with nothing stubbed between the argument
     * object and the interpreter. That is the seam the bug lived in: every other test in this class
     * hands the program over positionally, so the raw-JSON fallback passed for a program that had
     * chosen to print nothing.
     */
    @Test
    void aProgramSubmittedAsTheModelSubmitsItPrintsItsOutput() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path workspace = Files.createTempDirectory("router-model-args");
        ToolCatalog catalog = new ToolCatalog(CONFIG);
        ToolCallContext context = new ToolCallContext("owner", "repo", 1L, Workspace.unmanaged(workspace),
                ImplementationPlan.ToolRequest.builder()
                        .id("tool-1")
                        .tool("execute-code")
                        .args(ToolArguments.toPositional("execute-code",
                                node("{\"code\": \"print('CHARS', 11)\"}"),
                                catalog.schemaOf("execute-code").orElse(null)))
                        .build(),
                null);

        ToolResult result = router(mock(ToolExecutionService.class), botWithExecuteCode(),
                optedInSandbox())
                .execute(AgentToolRouter.Mode.CODING, context);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("CHARS 11");
    }

    /**
     * The shape that produced an empty result in the field: read a repository file, print a couple of
     * statistics about it. The checkout is not on the program's filesystem — the sandbox has its own
     * empty working directory — so the file is read through the tool surface like everything else.
     */
    @Test
    void aProgramReadsARepositoryFileThroughTheToolsAndPrintsItsStatistics() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path workspace = Files.createTempDirectory("router-repo-stats");
        Files.writeString(workspace.resolve("SWREQ.md"), "one\ntwo\nthree\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG));
        String program = """
                import ai_git_bot

                data = ai_git_bot.tools.call("cat", {"path": "SWREQ.md"})["output"]
                print("CHARS", len(data))
                print("LINES", data.count(chr(10)))
                """;

        ToolResult result = router(tools, botWithExecuteCode(),
                optedInSandbox())
                .execute(AgentToolRouter.Mode.CODING, context(workspace, program));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("CHARS ").contains("LINES ");
    }

    /**
     * The sweep the field report was attempting — glob a set of files, read each one, print a summary
     * — done the way the sandbox supports it: {@code find} lists, {@code cat} reads, both over the
     * bridge. {@code open()} is not an option, because the program has no checkout of its own; this
     * pins the recipe in the tool description as one that actually works.
     */
    @Test
    void aProgramSweepsTheCheckoutThroughTheToolSurface() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path workspace = Files.createTempDirectory("router-sweep");
        Files.createDirectories(workspace.resolve("items"));
        Files.writeString(workspace.resolve("items/mdeg_110100-11_SWREQ_1.md"), "# One\nalpha\nbravo\n");
        Files.writeString(workspace.resolve("items/mdeg_110100-11_SWREQ_2.md"), "# Two\ncharlie\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG));
        // `tools` bare, no import: that is the form the tool description gives the model.
        String program = """
                found = tools.call("find", {"args": ["items/mdeg_*_SWREQ_*.md"]})["output"]
                paths = [line.strip() for line in found.splitlines() if line.strip()]
                print("item files:", len(paths))
                for path in paths:
                    data = tools.call("cat", {"path": path})["output"]
                    print(path, "lines:", data.count(chr(10)))
                """;

        ToolResult result = router(tools, Set.of("execute-code", "find", "cat"),
                optedInSandbox())
                .execute(AgentToolRouter.Mode.CODING, context(workspace, program));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("item files: 2")
                .contains("mdeg_110100-11_SWREQ_1.md");
    }

    /**
     * A program reads repository state and nothing else. Writes, the branch switch and the build tools
     * stay model calls: the strategy classifies a round by the tools the model asked for, so a
     * mutation hidden inside a program would be booked as a read-only round.
     */
    @Test
    void aProgramCannotMutateTheWorkspaceThroughTheToolSurface() throws IOException {
        Assumptions.assumeTrue(pythonAvailable, "python3 is not installed");
        Path workspace = Files.createTempDirectory("router-nested-write");
        Files.writeString(workspace.resolve("README.md"), "readable\n");
        ToolExecutionService tools =
                new ToolExecutionService(CONFIG, new ToolCatalog(CONFIG));
        String program = """
                written = tools.call("write-file", {"path": "generated.txt", "content": "x"})
                print("write success:", written["success"])
                switched = tools.call("branch-switcher", {"branch": "release"})
                print("branch success:", switched["success"])
                read = tools.call("cat", {"path": "README.md"})
                print("read success:", read["success"], "|", read["output"].strip())
                """;

        ToolResult result = router(tools, Set.of("execute-code", "write-file", "branch-switcher", "cat"),
                optedInSandbox()).execute(AgentToolRouter.Mode.CODING, context(workspace, program));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("write success: False")
                .contains("branch success: False")
                .contains("read success: True | 1 | readable");
        assertThat(Files.exists(workspace.resolve("generated.txt")))
                .as("a program cannot write through the tool surface")
                .isFalse();
    }

}
