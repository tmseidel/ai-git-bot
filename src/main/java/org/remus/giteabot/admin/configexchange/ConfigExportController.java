package org.remus.giteabot.admin.configexchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * {@code /config/**} (see {@code SecurityConfig}, where {@code anyRequest().authenticated()}
 * covers it), and none should be added.</p>
 *
 * <p>The body carries every decrypted credential of the instance, so the response must not be
 * stored anywhere on the way: {@code Cache-Control: no-store} keeps browsers and proxies from
 * keeping a copy, and every fetch is logged so an export that nobody remembers making leaves a
 * trace.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConfigExportController {

    private static final ObjectMapper MAPPER = ConfigExchangeJson.mapper();

    private final ConfigExchangeService configExchangeService;

    @GetMapping("/config/export")
    public ResponseEntity<String> export(Authentication authentication) {
        String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(configExchangeService.export());
        log.info("Configuration exported (secrets included) for {}", actor(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"giteabot-config.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "an unknown caller" : "'" + authentication.getName() + "'";
    }
}
