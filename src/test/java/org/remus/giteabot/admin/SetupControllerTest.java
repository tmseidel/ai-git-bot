package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SetupController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class SetupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void setup_noAdminExists_showsSetupPage() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(true);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(view().name("setup"))
                .andExpect(content().string(containsString("/images/favicon.svg")))
                .andExpect(content().string(containsString("brand-icon")));
    }

    @Test
    void setup_adminExists_redirectsToLogin() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void createAdmin_validInput_createsAdminAndRedirects() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(true);

        mockMvc.perform(post("/setup")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "password123")
                        .param("confirmPassword", "password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(adminService).createAdmin("admin", "password123");
    }

    @Test
    void createAdmin_passwordTooShort_showsError() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(true);

        mockMvc.perform(post("/setup")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("error", "Password must be at least 8 characters"));

        verify(adminService, never()).createAdmin(anyString(), anyString());
    }

    @Test
    void createAdmin_passwordMismatch_showsError() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(true);

        mockMvc.perform(post("/setup")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "password123")
                        .param("confirmPassword", "different123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"))
                .andExpect(flash().attribute("error", "Passwords do not match"));

        verify(adminService, never()).createAdmin(anyString(), anyString());
    }

    @Test
    void login_noAdminExists_redirectsToSetup() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(true);

        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }

    @Test
    void login_adminExists_showsLoginPage() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(false);

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(containsString("/images/favicon.svg")))
                .andExpect(content().string(containsString("brand-icon")));
    }
}
