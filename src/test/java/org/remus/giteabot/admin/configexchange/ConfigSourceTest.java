package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void noEnvironmentMeansNoDocument() {
        assertTrue(new ConfigSource(name -> null).read().isEmpty());
        assertTrue(new ConfigSource(name -> "  ").read().isEmpty());
    }

    @Test
    void inlineJsonIsUsedWhenNoFileIsConfigured() {
        ConfigSource source = new ConfigSource(env("APP_CONFIG_JSON", "{\"bots\":[]}"));

        assertEquals(Optional.of("{\"bots\":[]}"), source.read());
    }

    @Test
    void fileWinsOverInlineJson() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"bots\":[{\"name\":\"from-file\"}]}", StandardCharsets.UTF_8);
        Map<String, String> env = new HashMap<>();
        env.put("APP_CONFIG_FILE", file.toString());
        env.put("APP_CONFIG_JSON", "{\"bots\":[]}");

        String document = new ConfigSource(env::get).read().orElseThrow();

        assertTrue(document.contains("from-file"));
    }

    @Test
    void unreadableFileIsAnEmptyDocument() {
        ConfigSource source = new ConfigSource(env("APP_CONFIG_FILE",
                tempDir.resolve("does-not-exist.json").toString()));

        assertTrue(source.read().isEmpty(), "a broken path must not break startup");
    }

    private static java.util.function.UnaryOperator<String> env(String key, String value) {
        return name -> key.equals(name) ? value : null;
    }
}
