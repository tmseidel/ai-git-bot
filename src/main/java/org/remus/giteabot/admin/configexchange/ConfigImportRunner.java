package org.remus.giteabot.admin.configexchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AdminService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the configuration document once, at startup.
 *
 * <p>The gate is "no admin user exists", which is what makes the import apply only into
 * an empty database: in a throw-away instance that is every boot, in a persistent one it
 * is the first boot of an empty volume and never again. A restart therefore cannot
 * overwrite data an operator created through the UI.</p>
 *
 * <p>Runs after Flyway and after the servlet container binds, so a broken document
 * leaves a sub-second window in which {@code /setup} is still reachable — the intended
 * fallback. Failures never abort the container: an unconfigured but running instance is
 * more useful than a dead one.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigImportRunner implements ApplicationRunner {

    private static final ObjectMapper MAPPER = ConfigExchangeJson.mapper();

    private final ConfigSource source;
    private final ConfigExchangeService configExchangeService;
    private final AdminService adminService;
    private final ConfigMode configMode;

    @Override
    public void run(ApplicationArguments args) {
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
            log.error("Configuration document is not valid JSON ({}); starting unconfigured",
                    e.getOriginalMessage());
            return;
        }
        try {
            int created = configExchangeService.importDocument(root);
            configMode.markApplied(created);
            log.info("Config import applied: {} rows created. Remove APP_CONFIG_JSON / "
                    + "APP_CONFIG_FILE once the instance is set up: the document contains "
                    + "credentials in plain text.", created);
        } catch (RuntimeException e) {
            log.error("Config import failed ({}); starting unconfigured — complete the setup "
                    + "at /setup", e.getMessage());
        }
    }
}
