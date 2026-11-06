package org.remus.giteabot.admin.configexchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Resolves the raw configuration document.
 *
 * <p>Read straight from the environment rather than through Spring property binding:
 * the payload is JSON, so property-placeholder resolution would mangle any
 * {@code ${...}} inside a credential and relaxed binding would rewrite the keys.
 * {@code APP_CONFIG_FILE} wins over {@code APP_CONFIG_JSON} when both are set.</p>
 */
@Slf4j
@Component
public class ConfigSource {

    static final String ENV_FILE = "APP_CONFIG_FILE";
    static final String ENV_JSON = "APP_CONFIG_JSON";

    private final UnaryOperator<String> env;

    public ConfigSource() {
        this(System::getenv);
    }

    /** Test seam: lets a test supply the environment without touching the process. */
    ConfigSource(UnaryOperator<String> env) {
        this.env = env;
    }

    public Optional<String> read() {
        String file = env.apply(ENV_FILE);
        if (file != null && !file.isBlank()) {
            try {
                return Optional.of(Files.readString(Path.of(file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.error("Configuration file '{}' is not readable ({}); starting unconfigured",
                        file, e.getMessage());
                return Optional.empty();
            }
        }
        String json = env.apply(ENV_JSON);
        return (json == null || json.isBlank()) ? Optional.empty() : Optional.of(json);
    }
}
