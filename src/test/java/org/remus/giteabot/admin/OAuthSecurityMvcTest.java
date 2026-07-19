package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(value = OAuthSecurityMvcTest.ProtectedController.class,
        properties = "giteabot.security.login-method=oauth")
@Import({SecurityConfig.class, OAuthLoginController.class, OAuthSecurityMvcTest.OAuthClientTestConfiguration.class})
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
class OAuthSecurityMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedUiRequestRedirectsDirectlyToOAuthAuthorizationEndpoint() throws Exception {
        mockMvc.perform(get("/protected"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/giteabot"));
    }

    @Test
    void oauthFailurePageIsAccessibleWithoutStartingAnotherLogin() throws Exception {
        mockMvc.perform(get("/oauth2/error"))
                .andExpect(status().isOk())
                .andExpect(view().name("oauth-login-error"));
    }

    @Controller
    static class ProtectedController {

        @GetMapping("/protected")
        @ResponseBody
        String protectedPage() {
            return "protected";
        }
    }

    @TestConfiguration
    static class OAuthClientTestConfiguration {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration registration = ClientRegistration.withRegistrationId(OAuthLoginConfiguration.REGISTRATION_ID)
                    .clientId("client-id")
                    .clientSecret("client-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/giteabot")
                    .scope("openid", "profile")
                    .authorizationUri("https://login.example.test/authorize")
                    .tokenUri("https://login.example.test/token")
                    .userInfoUri("https://login.example.test/userinfo")
                    .jwkSetUri("https://login.example.test/keys")
                    .userNameAttributeName("sub")
                    .clientName("test")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
