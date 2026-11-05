package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extends the default tool configuration with every built-in tool the running build
 * actually ships — except the opt-in ones ({@link BotToolConfigurationService#OPT_IN_TOOLS}).
 *
 * <p>The Flyway seed can only list the tools that existed when it was written, and the
 * default configuration cannot be edited through the UI
 * ({@link BotToolSelectionService#saveSelection} rejects it) — so without this step a bot
 * that never customised its tool set silently loses every tool added after the seed, and a
 * freshly imported instance (see {@code admin/configexchange}) would start with a stale
 * tool set.</p>
 *
 * <p>Code execution stays out of it. The row this class writes cannot be edited afterwards,
 * so a tool added here is permanent and reaches every bot that never attached a
 * configuration of its own: {@code execute-code} would become opt-out for exactly the
 * deployments that never made a decision about it, on the next boot, without an operator
 * having asked. It runs a model-written program as this service user (or as a sandbox
 * identity where the deployment names a pool), so an operator attaches a tool configuration
 * that selects it when a bot should have it. {@code ExecuteCodeOptInConfigurationTest} keeps
 * pinning the migrations for the same reason, and {@code DefaultToolSetInitializerTest} pins
 * this class.</p>
 *
 * <p>Additive only, and only over the tools it does manage: nothing is ever removed. That
 * promise is about an operator's <em>own</em> configuration — in the Default row nobody can
 * disable anything, so a tool that landed there could never be taken back. Which is why the
 * opt-in tools are excluded by name rather than added and left for an operator to
 * remove.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultToolSetInitializer implements ApplicationRunner {

    private final BotToolConfigurationService botToolConfigurationService;
    private final BuiltinToolRegistry builtinToolRegistry;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        // The catalog as it is: addMissingTools owns the opt-in policy (OPT_IN_TOOLS) and
        // skips those tools whatever it is handed.
        List<BuiltinToolRegistry.BuiltinTool> selectable = builtinToolRegistry.builtinTools();
        botToolConfigurationService.findDefault().ifPresent(configuration -> {
            int added = botToolConfigurationService.addMissingTools(configuration.getId(), selectable);
            if (added > 0) {
                log.info("Default tool configuration extended with {} newly available tool(s)", added);
            }
        });
    }
}
