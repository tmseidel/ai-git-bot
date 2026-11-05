package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.tools.ToolKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default tool configuration must expose what the running build ships — but never the
 * opt-in tools.
 *
 * <p>The seeded row cannot be edited through the UI and the initializer puts back whatever it
 * lacks on every boot, so {@code execute-code} in it would be permanent and opt-out for every
 * bot that never attached a tool configuration of its own. {@code
 * ExecuteCodeOptInConfigurationTest} pins the migrations; this test pins the initializer, which
 * runs against the live database.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultToolSetInitializerTest {

    @Autowired private DefaultToolSetInitializer initializer;
    @Autowired private BotToolConfigurationService botToolConfigurationService;
    @Autowired private BotToolSelectionRepository botToolSelectionRepository;
    @Autowired private BuiltinToolRegistry builtinToolRegistry;

    @Test
    void defaultConfigurationSelectsEveryAvailableToolExceptTheOptInOnes() {
        // Idempotent, and it also restores the row if another test overrode it.
        initializer.run(null);

        List<String> selected = selectedToolNames();

        List<String> expected = builtinToolRegistry.builtinTools().stream()
                .map(BuiltinToolRegistry.BuiltinTool::name)
                .filter(name -> !BotToolConfigurationService.OPT_IN_TOOLS.contains(name))
                .toList();
        assertThat(selected).containsAll(expected);
        assertThat(selected).doesNotContain("execute-code");
    }

    @Test
    void aSecondRunRestoresNothingIncludingTheOptInTools() {
        initializer.run(null);
        Long defaultId = botToolConfigurationService.findDefault().orElseThrow().getId();

        // The whole catalog, opt-in tools included: the service is where the policy lives, so a
        // caller cannot hand code execution to the immutable row.
        assertEquals(0, botToolConfigurationService.addMissingTools(defaultId,
                builtinToolRegistry.builtinTools()));
        assertThat(selectedToolNames()).doesNotContain("execute-code");
    }

    @Test
    void everyAgentControlToolIsOptIn() {
        Set<String> agentControl = builtinToolRegistry.builtinTools().stream()
                .filter(tool -> tool.kind() == ToolKind.AGENT_CONTROL)
                .map(BuiltinToolRegistry.BuiltinTool::name)
                .collect(Collectors.toSet());

        assertThat(BotToolConfigurationService.OPT_IN_TOOLS)
                .describedAs("a new agent-control tool must be a decision, not a default")
                .containsAll(agentControl);
    }

    private List<String> selectedToolNames() {
        Long defaultId = botToolConfigurationService.findDefault().orElseThrow().getId();
        return botToolSelectionRepository
                .findByConfigurationIdOrderByToolKindAscToolNameAsc(defaultId).stream()
                .map(BotToolSelection::getToolName)
                .toList();
    }
}
