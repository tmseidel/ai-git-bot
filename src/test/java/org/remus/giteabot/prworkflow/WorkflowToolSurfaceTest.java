package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The surface a PR-workflow run offers. Before it existed, a workflow agent advertised only its
 * own {@code ALLOWED_TOOLS} and dispatched every name to its own executor, so no catalogue tool
 * reached those runs — {@code execute-code} included, despite its {@code Role.PR_WORKFLOW}.
 *
 * <p>The read-only catalogue tools and {@code execute-code} are not a standing entitlement of a
 * workflow run: the surface adds only the ones the bot's tool configuration selects, exactly as
 * the coding, writer and agent-review runs do. {@link #aBotWithoutExecuteCodeSelectedCannotReachIt}
 * pins that — a deployment that never opted in must not get the sandbox on a workflow run.</p>
 */
class WorkflowToolSurfaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OWN = Set.of("doc-write", "doc-delete");

    /** A Default-style selection: the read-only catalogue tools, the program tool, and noise. */
    private static final Set<String> BOT_TOOLS = Set.of(
            "rg", "find", "cat", "tree", "git-log", "git-blame",
            "ctags-signatures", "ctags-deps", "pr-diff", "get-issue", "search-issues",
            "execute-code", "branch-switcher", "write-file", "mvn");

    private final ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());

    private WorkflowToolSurface surface(Set<String> botTools, AgentToolRouter router) {
        return new WorkflowToolSurface(OWN, catalog, McpToolCatalog.empty(),
                WorkflowToolSurface.callableTools(OWN, botTools), router,
                "acme", "repo", 42L, Workspace.unmanaged(Path.of("/tmp/ws")));
    }

    private AgentToolRouter router(Set<String> whitelist) {
        return new AgentToolRouter(
                mock(ToolExecutionService.class), catalog, mock(McpOrchestrationService.class),
                mock(McpConfiguration.class), McpToolCatalog.empty(), mock(RepositoryApiClient.class),
                whitelist, null);
    }

    @Test
    void advertisesTheWorkflowsOwnToolsAndTheSelectedCatalogueTools() {
        List<String> names = surface(BOT_TOOLS, null).advertised().stream()
                .map(ToolDescriptor::name).toList();

        assertThat(names).contains("doc-write", "doc-delete", "execute-code", "cat", "rg", "find",
                "ctags-signatures", "pr-diff", "get-issue", "search-issues");
        // execute-code is declared for PR_WORKFLOW and WRITER alike — it must be offered once.
        assertThat(names).doesNotHaveDuplicates();
        // branch-switcher moves the checkout the run is about to commit to; the bot may select it,
        // but the workflow run still must not offer it — nor the coding and validation tools.
        assertThat(names).doesNotContain("branch-switcher", "write-file", "mvn");
    }

    @Test
    void callableToolsAddsCatalogueExtrasOnlyWhenTheBotSelectsThem() {
        Set<String> selected = WorkflowToolSurface.callableTools(OWN, BOT_TOOLS);

        assertThat(selected).contains("doc-write", "execute-code", "cat", "rg");
        assertThat(selected).containsAll(WorkflowToolSurface.READ_ONLY_CATALOGUE_TOOLS);

        Set<String> narrowedTools = new LinkedHashSet<>(BOT_TOOLS);
        narrowedTools.remove("execute-code");
        narrowedTools.remove("cat");
        Set<String> narrowed = WorkflowToolSurface.callableTools(OWN, narrowedTools);

        assertThat(narrowed).contains("doc-write").doesNotContain("execute-code", "cat");
    }

    @Test
    void aBotWithoutExecuteCodeSelectedCannotReachIt() {
        Set<String> botTools = new LinkedHashSet<>(BOT_TOOLS);
        botTools.remove("execute-code");
        Set<String> callable = WorkflowToolSurface.callableTools(OWN, botTools);

        // The factory wires the surface and the router off the same set; reproduce that here.
        WorkflowToolSurface surface = surface(botTools, router(callable));

        assertThat(surface.callable()).doesNotContain("execute-code");
        assertThat(surface.advertised()).extracting(ToolDescriptor::name).doesNotContain("execute-code");
        // And the router's whitelist refuses it in the executor's own words.
        assertThat(surface.executeRouted("execute-code", JSON.createObjectNode().put("code", "print(1)")))
                .contains("not enabled for this bot");
    }

    @Test
    void aWorkflowToolIsHandledLocallyAndACatalogueToolIsNot() {
        WorkflowToolSurface surface = surface(BOT_TOOLS, null);

        assertThat(surface.handles("doc-write")).isTrue();
        assertThat(surface.handles("DOC-WRITE")).isTrue();
        assertThat(surface.handles("rg")).isFalse();
        assertThat(surface.handles("execute-code")).isFalse();
        assertThat(surface.handles(null)).isFalse();
    }

    @Test
    void withoutARouterACatalogueCallIsRefusedWithAnExplanation() {
        String result = surface(BOT_TOOLS, null)
                .executeRouted("cat", JSON.createObjectNode().put("path", "README.md"));

        assertThat(result).contains("not available in this run");
    }

    @Test
    void aCatalogueCallReachesTheRouterSoTheWhitelistDecides() {
        // An empty bot whitelist: the router, not the surface, owns the refusal.
        String result = surface(BOT_TOOLS, router(Set.of()))
                .executeRouted("cat", JSON.createObjectNode().put("path", "README.md"));

        assertThat(result).contains("not enabled for this bot");
    }

    @Test
    void executeCodeIsDispatchedToTheRouterAndReportsAnUnavailableSandbox() {
        // Whitelisted, but this deployment wires no sandbox: the call must come back in
        // execute-code's own words rather than being swallowed by the workflow's executor.
        WorkflowToolSurface surface = surface(BOT_TOOLS,
                router(WorkflowToolSurface.callableTools(OWN, BOT_TOOLS)));

        String result = surface.executeRouted("execute-code",
                JSON.createObjectNode().put("code", "print(1)"));

        assertThat(result).contains("execute-code is not available in this deployment");
    }
}
