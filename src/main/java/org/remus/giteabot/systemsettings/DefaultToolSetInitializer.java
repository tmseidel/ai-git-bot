package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extends the default tool configuration with every built-in tool the running build
 * actually ships.
 *
 * <p>The Flyway seed can only list the tools that existed when it was written, and the
 * default configuration cannot be edited through the UI
 * ({@link BotToolSelectionService#saveSelection} rejects it) — so without this step a bot
 * that never customised its tool set silently loses every tool added after the seed, and a
 * freshly imported instance (see {@code admin/configexchange}) would start with a stale
 * tool set.</p>
 *
 * <p>Everything the build ships is included, {@code execute-code} too: the default set answers
 * "what may a bot do at all", and the default row cannot be edited afterwards, so a tool left
 * out here can never be given back to it later. An operator who does not want a bot running
 * model-written programs attaches a tool configuration of their own instead — the selection
 * travels with the bot, not with this seed. {@code ExecuteCodeOptInConfigurationTest} keeps
 * pinning the migrations, which must not seed it: a migrated row would be permanent in
 * databases whose operator never sees this class run.</p>
 *
 * <p>Additive only: nothing is ever removed, so an operator who deliberately disabled a
 * tool keeps that decision.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultToolSetInitializer implements ApplicationRunner {

    private final BotToolConfigurationService botToolConfigurationService;
    private final BuiltinToolRegistry builtinToolRegistry;

    @Override
    public void run(ApplicationArguments args) {
        List<BuiltinToolRegistry.BuiltinTool> selectable = builtinToolRegistry.builtinTools();
        botToolConfigurationService.findDefault().ifPresent(configuration -> {
            int added = botToolConfigurationService.addMissingTools(configuration.getId(), selectable);
            if (added > 0) {
                log.info("Default tool configuration extended with {} newly available tool(s)", added);
            }
        });
    }
}
