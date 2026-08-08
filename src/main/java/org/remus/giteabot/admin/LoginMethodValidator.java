package org.remus.giteabot.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast validation of the web login method. A typo in
 * {@code giteabot.security.login-method} must not silently leave the app
 * without any web security chain (fail-open) - refuse to start instead.
 */
@Configuration
public class LoginMethodValidator {

    public LoginMethodValidator(
            @Value("${giteabot.security.login-method:native}") String loginMethod) {
        if (!"native".equals(loginMethod) && !"oauth".equals(loginMethod)) {
            throw new IllegalStateException(
                    "Invalid giteabot.security.login-method: '" + loginMethod
                            + "'. Must be 'native' or 'oauth'. Refusing to start without a defined web security chain.");
        }
    }
}
