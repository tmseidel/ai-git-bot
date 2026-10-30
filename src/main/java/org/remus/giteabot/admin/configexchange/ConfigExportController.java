package org.remus.giteabot.admin.configexchange;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the current configuration as the same document the importer accepts.
 *
 * <p>This is what makes a UI-configured instance convertible: export it, paste the
 * result into {@code APP_CONFIG_JSON}, and the instance can be recreated anywhere. It
 * is also the machine-written reference implementation of the format, so the website's
 * generator has something to be compared against.</p>
 *
 * <p>Authenticated by the normal admin security chain — no permit rule exists for
 * {@code /config/**}, and none should be added.</p>
 */
@RestController
@RequiredArgsConstructor
public class ConfigExportController {

    private static final ObjectMapper MAPPER = ConfigExchangeJson.mapper();

    private final ConfigExchangeService configExchangeService;

    @GetMapping("/config/export")
    public ResponseEntity<String> export() {
        String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(configExchangeService.export());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"giteabot-config.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }
}
