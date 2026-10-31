package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default tool configuration must expose everything the running build ships, {@code
 * execute-code} included: the seeded row cannot be edited through the UI, so a tool missing from
 * it can never be given to a bot that uses the default configuration. Only the migrations stay
 * free of it — {@code ExecuteCodeOptInConfigurationTest} keeps pinning that.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultToolSetInitializerTest {

    @Autowired private DefaultToolSetInitializer initializer;
    @Autowired private BotToolConfigurationService botToolConfigurationService;
    @Autowired private BotToolSelectionRepository botToolSelectionRepository;
    @Autowired private BuiltinToolRegistry builtinToolRegistry;

    @Test
    void defaultConfigurationSelectsEveryAvailableTool() {
        // Idempotent, and it also restores the row if another test overrode it.
        initializer.run(null);

        List<String> selected = selectedToolNames();

        List<String> expected = builtinToolRegistry.builtinTools().stream()
                .map(BuiltinToolRegistry.BuiltinTool::name)
                .toList();
        assertThat(selected).containsAll(expected);
        assertThat(selected).contains("execute-code");
    }

    @Test
    void runningAgainAddsNothing() {
        initializer.run(null);
        Long defaultId = botToolConfigurationService.findDefault().orElseThrow().getId();

        assertEquals(0, botToolConfigurationService.addMissingTools(defaultId,
                builtinToolRegistry.builtinTools()));
    }

    private List<String> selectedToolNames() {
        Long defaultId = botToolConfigurationService.findDefault().orElseThrow().getId();
        return botToolSelectionRepository
                .findByConfigurationIdOrderByToolKindAscToolNameAsc(defaultId).stream()
                .map(BotToolSelection::getToolName)
                .toList();
    }
}
