package org.remus.giteabot.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Displays OAuth failures without routing back through the provider-login entry point. */
@Slf4j
@Controller
@ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "oauth")
public class OAuthLoginController {

    @GetMapping("/oauth2/error")
    public String loginError(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        AuthenticationException exception = session == null ? null
                : (AuthenticationException) session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        if (exception != null) {
            log.warn("OAuth login failed: {}", exception.getMessage());
        }
        model.addAttribute("error", "OAuth sign-in failed. Check the server logs and your OAuth client configuration, then try again.");
        return "oauth-login-error";
    }
}
