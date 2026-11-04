package org.remus.giteabot.admin.configexchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AdminService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the configuration document once, before the servlet container binds its port.
 *
 * <p>The gate is "no admin user exists", which is what makes the import apply only into
 * an empty database: in a throw-away instance that is every boot, in a persistent one it
 * is the first boot of an empty volume and never again. A restart therefore cannot
 * overwrite data an operator created through the UI.</p>
 *
 * <p>Runs as a {@link SmartInitializingSingleton}: every singleton — the Flyway migration
 * initializer, the repositories, the services — exists by then, and the web server has not
 * been started yet (that happens in {@code finishRefresh}, after singleton instantiation).
 * An {@code ApplicationRunner} would instead run after the port is bound, which leaves a
 * check-then-act window on the first boot of a public trial instance: {@code /setup} is a
 * permit-all page, so a request arriving between the "is the database empty" question and
 * the import could create an account of its own — the import would then skip a database it
 * was meant to fill, or both accounts would exist, and on an in-memory database the window
 * reopens on every restart.</p>
 *
 * <p>Failures never abort the container: an unconfigured but running instance is more
 * useful than a dead one, and {@code /setup} is the fallback for a document that cannot be
 * applied. {@link ConfigMode#markFailed()} makes the banner say so instead of blaming a
 * database that is not the problem.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigImportRunner implements SmartInitializingSingleton {

    private static final ObjectMapper MAPPER = ConfigExchangeJson.mapper();

    private final ConfigSource source;
    private final ConfigExchangeService configExchangeService;
    private final AdminService adminService;
    private final ConfigMode configMode;

    @Override
    public void afterSingletonsInstantiated() {
        source.read().ifPresent(this::apply);
    }

    private void apply(String json) {
        configMode.markDocumentPresent();
        if (!adminService.isSetupRequired()) {
            log.info("Config import skipped: the database is not empty (an admin user exists)");
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            configMode.markFailed();
            log.error("Configuration document is not valid JSON ({}); starting unconfigured — "
                    + "complete the setup at /setup", e.getOriginalMessage());
            return;
        }
        try {
            int created = configExchangeService.importDocument(root);
            configMode.markApplied(created);
            log.info("Config import applied: {} rows created. Remove APP_CONFIG_JSON / "
                    + "APP_CONFIG_FILE once the instance is set up: the document contains "
                    + "credentials in plain text.", created);
        } catch (RuntimeException e) {
            configMode.markFailed();
            log.error("Config import failed ({}); starting unconfigured — complete the setup "
                    + "at /setup", e.getMessage());
        }
    }
}
