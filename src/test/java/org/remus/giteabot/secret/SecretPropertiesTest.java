package org.remus.giteabot.secret;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link SecretProperties}: they pin down the configuration keys the
 * documentation promises, including the environment-variable spelling used in
 * {@code docker-compose.yml}.
 */
class SecretPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            // a developer or CI runner that has GITEABOT_SECRET_ENV_WHITELIST exported must not
            // change what these tests see - every test below supplies its own configuration
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));

    @Test
    void testEmptyPropertyBindsToEmptyWhitelist() {
        // this is what the ${GITEABOT_SECRET_ENV_WHITELIST:} default in application.properties resolves to
        runner.withPropertyValues("giteabot.secret.env.whitelist=")
                .run(context -> assertThat(context.getBean(SecretProperties.class).getEnv().getWhitelist()).isEmpty());
    }

    @Test
    void testWhitelistBindsFromEnvironmentVariable() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("GITEABOT_SECRET_ENV_WHITELIST", "MY_API_TOKEN,CI_DEPLOY_KEY"))))
                .run(context -> assertThat(context.getBean(SecretProperties.class).getEnv().getWhitelist())
                        .containsExactly("MY_API_TOKEN", "CI_DEPLOY_KEY"));
    }

    @Test
    void testConfiguredWhitelistReachesTheEnvSecretSource() {
        runner.withPropertyValues("giteabot.secret.env.whitelist=AI_GIT_BOT_TEST_CONFIGURED")
                .run(context -> {
                    EnvSecretSource source = new EnvSecretSource(context.getBean(SecretProperties.class));

                    // whitelisted but undefined in this JVM: resolves to nothing, but no longer refused
                    assertThat(source.resolve("AI_GIT_BOT_TEST_CONFIGURED")).isEmpty();
                });
    }

    @Configuration
    @EnableConfigurationProperties(SecretProperties.class)
    static class TestConfig {
    }
}
