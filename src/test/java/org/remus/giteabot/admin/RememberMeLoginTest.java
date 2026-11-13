package org.remus.giteabot.admin;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context guard for the "remember me" checkbox: the token must be stored in
 * {@code persistent_logins}, must authenticate a request without a session, and must
 * be revoked by logout.
 *
 * <p>The test profile disables Flyway and builds the schema from the JPA entities, so
 * the token table is created per test method.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(statements = "create table if not exists persistent_logins ("
        + "username varchar(64) not null, "
        + "series varchar(64) primary key, "
        + "token varchar(64) not null, "
        + "last_used timestamp not null)")
class RememberMeLoginTest {

    private static final String USERNAME = "remember-me-admin";
    private static final String PASSWORD = "password123";

    @Autowired private WebApplicationContext wac;
    @Autowired private AdminService adminService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (adminService.findByUsername(USERNAME).isEmpty()) {
            adminService.createAdmin(USERNAME, PASSWORD);
        }
    }

    @Test
    void loginPage_showsRememberMeCheckbox() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"remember-me\"")))
                .andExpect(content().string(containsString("Remember me")));
    }

    @Test
    void loginWithRememberMe_issuesRememberMeCookie() throws Exception {
        mvc.perform(loginRequest(true))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(cookie().exists("remember-me"));
    }

    @Test
    void loginWithoutRememberMe_issuesNoCookie() throws Exception {
        mvc.perform(loginRequest(false))
                .andExpect(status().isFound())
                .andExpect(cookie().doesNotExist("remember-me"));
    }

    @Test
    void rememberMeCookie_authenticatesRequestWithoutSession() throws Exception {
        Cookie rememberMe = rememberMeCookie();

        mvc.perform(get("/dashboard").cookie(rememberMe))
                .andExpect(status().isOk());
    }

    @Test
    void logout_revokesRememberMeToken() throws Exception {
        MvcResult login = mvc.perform(loginRequest(true)).andReturn();
        Cookie rememberMe = login.getResponse().getCookie("remember-me");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(rememberMe).isNotNull();

        mvc.perform(post("/logout").session(session).cookie(rememberMe).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?logout"));

        mvc.perform(get("/dashboard").cookie(rememberMe))
                .andExpect(status().is3xxRedirection());
    }

    private MockHttpServletRequestBuilder loginRequest(boolean rememberMe) {
        var request = post("/login")
                .param("username", USERNAME)
                .param("password", PASSWORD)
                .with(csrf());
        return rememberMe ? request.param("remember-me", "true") : request;
    }

    private Cookie rememberMeCookie() throws Exception {
        MvcResult login = mvc.perform(loginRequest(true)).andReturn();
        Cookie rememberMe = login.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        return rememberMe;
    }
}
