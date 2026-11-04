package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The export body holds every decrypted credential of the instance.
 *
 * <p>Two things therefore have to hold: nobody without an admin session gets it (the security
 * chain really does cover {@code /config/**} — this controller adds no permit rule), and no
 * cache on the way keeps a copy.</p>
 */
@WebMvcTest(ConfigExportController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class ConfigExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfigExchangeService configExchangeService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void theExportIsNeverCached() throws Exception {
        when(configExchangeService.export()).thenReturn(ConfigExchangeJson.mapper().createObjectNode());

        mockMvc.perform(get("/config/export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void theExportRequiresAnAdminSession() throws Exception {
        mockMvc.perform(get("/config/export"))
                .andExpect(status().is3xxRedirection());
    }
}
