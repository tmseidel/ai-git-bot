package org.remus.giteabot.secret;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Secret-resolution configuration, prefix {@code giteabot.secret}. All properties are
 * env-overridable ({@code GITEABOT_SECRET_ENV_WHITELIST}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "giteabot.secret")
public class SecretProperties {

    private Env env = new Env();

    @Data
    public static class Env {

        /**
         * Names of the environment variables an {@code ${env:NAME}} reference is allowed to
         * read, as a comma-separated list. Empty by default, which disables the environment
         * secret source entirely; a reference to a name that is not listed stays unresolved
         * instead of failing.
         * <p>
         * Names are matched verbatim, so they are case-sensitive just like the environment
         * of the process itself is on Linux.
         */
        private List<String> whitelist = List.of();
    }
}
