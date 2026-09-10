package org.remus.giteabot.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link EnvSecretSource}.
 * <p>
 * The whitelist comes from {@link SecretProperties} and is therefore set up per test.
 * The value lookup still goes through the real {@code System.getenv} - the process
 * environment cannot be changed from within the JVM - so the resolving tests pick an
 * environment variable the test JVM actually has and skip themselves if there is none.
 */
class EnvSecretSourceTest {

    private static final String NOT_WHITELISTED = "AI_GIT_BOT_TEST_NOT_WHITELISTED";
    private static final String WHITELISTED_BUT_UNSET = "AI_GIT_BOT_TEST_MISSING_VARIABLE";

    @Test
    void testTypeIsEnv() {
        assertThat(source().type()).isEqualTo("env");
    }

    @Test
    void testIsRetrievableFromTheRegistryByItsDeclaredType() {
        // ${env:...} only resolves if the registry indexes this source under the same name
        EnvSecretSource source = source();
        SecretSourceRegistry registry = new SecretSourceRegistry(List.of(source));

        assertThat(registry.retrieve(source.type())).containsSame(source);
    }

    @ParameterizedTest(name = "''{0}'' is rejected as an environment variable name")
    @ValueSource(strings = {
            "",
            "1TOKEN",       // must not start with a digit
            "MY-TOKEN",
            "MY TOKEN",
            "MY.TOKEN",
            "MY$TOKEN",
            "TOKEN!",
            "TÖKEN"
    })
    void testResolveRejectsInvalidKeys(String key) {
        EnvSecretSource source = source(key);

        // even a whitelisted name has to be a syntactically valid variable name
        assertThatThrownBy(() -> source.resolve(key))
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining(key);
    }

    @Test
    void testResolveRejectsNullKey() {
        EnvSecretSource source = source();

        assertThatThrownBy(() -> source.resolve(null))
                .isInstanceOf(KeyResolveException.class)
                .hasMessage("Key should not be empty.");
    }

    @ParameterizedTest(name = "''{0}'' is an acceptable environment variable name")
    @ValueSource(strings = {"T", "TOKEN", "my_token", "Token_1", "a1", "_TOKEN"})
    void testResolveAcceptsValidKeys(String key) {
        // valid, but not whitelisted - so it resolves to nothing instead of throwing
        assertThat(source().resolve(key)).isEmpty();
    }

    @Test
    void testResolveReturnsEmptyForKeyOutsideTheWhitelist() {
        EnvSecretSource source = source("SOMETHING_ELSE");

        assertThat(source.resolve(NOT_WHITELISTED)).isEmpty();
    }

    @Test
    void testResolveReturnsEmptyWithoutAnyWhitelistedName() {
        assertThat(source().resolve(NOT_WHITELISTED)).isEmpty();
    }

    @Test
    void testResolveReturnsEmptyForWhitelistedButUndefinedVariable() {
        assumeTrue(System.getenv(WHITELISTED_BUT_UNSET) == null,
                "test expects " + WHITELISTED_BUT_UNSET + " to be undefined");

        assertThat(source(WHITELISTED_BUT_UNSET).resolve(WHITELISTED_BUT_UNSET)).isEmpty();
    }

    @Test
    void testResolveReturnsTheValueOfAWhitelistedVariable() {
        String key = anExistingEnvironmentVariable();

        Optional<SecretValue> resolved = source(key).resolve(key);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo(System.getenv(key));
        assertThat(resolved.get().sourceType()).isEqualTo("env");
        assertThat(resolved.get().key()).isEqualTo(key);
    }

    @Test
    void testWhitelistEntriesAreTrimmed() {
        String key = anExistingEnvironmentVariable();

        // "A, B" in the property file must not whitelist " B"
        EnvSecretSource source = source("SOMETHING_ELSE", "  " + key + "  ");

        assertThat(source.resolve(key)).isPresent();
    }

    @Test
    void testBlankAndNullWhitelistEntriesAreIgnored() {
        EnvSecretSource source = new EnvSecretSource(properties(Arrays.asList("", "   ", null)));

        assertThat(source.resolve(NOT_WHITELISTED)).isEmpty();
    }

    @Test
    void testWhitelistIsMatchedCaseSensitively() {
        String key = anExistingEnvironmentVariable();
        String otherCase = key.equals(key.toUpperCase()) ? key.toLowerCase() : key.toUpperCase();
        assumeTrue(!otherCase.equals(key), "test needs a variable name with both cases");

        EnvSecretSource source = source(key);

        assertThat(source.resolve(key)).isPresent();
        assertThat(source.resolve(otherCase)).isEmpty();
    }

    @Test
    void testWhitelistIsEmptyByDefault() {
        EnvSecretSource source = new EnvSecretSource(new SecretProperties());

        assertThat(source.resolve(NOT_WHITELISTED)).isEmpty();
    }

    // ---------- Blacklist: variables the application itself reads ----------

    @ParameterizedTest(name = "whitelisting ''{0}'' prevents the application from starting")
    @ValueSource(strings = {
            "GITEABOT_SECURITY_OAUTH_CLIENT_SECRET",
            "GITEABOT_SECURITY_AUTO_LOGIN_PASSWORD",
            "GITEABOT_SECRET_ENV_WHITELIST",
            "SPRING_DATASOURCE_PASSWORD",
            "DATABASE_PASSWORD",
            "APP_ENCRYPTION_KEY"
    })
    void testConstructorRejectsBlacklistedWhitelistEntry(String key) {
        assertThatThrownBy(() -> source(key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("giteabot.secret.env.whitelist")
                .hasMessageContaining(key);
    }

    @Test
    void testConstructorNamesEveryBlacklistedEntry() {
        assertThatThrownBy(() -> source("MY_API_TOKEN", "DATABASE_PASSWORD", "APP_ENCRYPTION_KEY"))
                .isInstanceOf(IllegalStateException.class)
                // sorted, so the message is stable regardless of the whitelist's iteration order
                .hasMessageContaining("[APP_ENCRYPTION_KEY, DATABASE_PASSWORD]")
                .hasMessageNotContaining("MY_API_TOKEN");
    }

    @ParameterizedTest(name = "''{0}'' is close to a blacklisted name but still allowed")
    @ValueSource(strings = {
            "MY_DATABASE_PASSWORD",     // the blacklist matches the whole name, not a substring
            "APP_ENCRYPTION_KEYS",
            "APP_PUBLIC_URL",           // only APP_ENCRYPTION_KEY is reserved, not the whole APP_ prefix
            "GITEABOT",                 // the prefix patterns require the trailing underscore
            "SPRINGBOARD_TOKEN",
            "database_password"         // matched case-sensitively, like the whitelist itself
    })
    void testConstructorAcceptsNamesOutsideTheBlacklist(String key) {
        assertThatCode(() -> source(key)).doesNotThrowAnyException();
    }

    @Test
    void testConstructorAcceptsEmptyWhitelist() {
        assertThatCode(EnvSecretSourceTest::source).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "resolving ''{0}'' is refused")
    @ValueSource(strings = {
            "GITEABOT_SECURITY_OAUTH_CLIENT_SECRET",
            "SPRING_DATASOURCE_PASSWORD",
            "DATABASE_PASSWORD",
            "APP_ENCRYPTION_KEY"
    })
    void testResolveRejectsBlacklistedKey(String key) {
        // the constructor already prevents whitelisting these, so this is the diagnostic
        // for a ${env:...} reference naming one: fail loudly instead of silently sending the literal
        assertThatThrownBy(() -> source().resolve(key))
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining(key)
                .hasMessageContaining("the application itself already uses it");
    }

    @Test
    void testInvalidNamesAreReportedBeforeTheBlacklist() {
        assertThatThrownBy(() -> source().resolve("DATABASE-PASSWORD"))
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining("must start with a letter");
    }

    /**
     * Picks an environment variable of the test JVM whose name is accepted by
     * {@link EnvSecretSource} and that carries a value.
     */
    private static String anExistingEnvironmentVariable() {
        Optional<String> candidate = System.getenv().entrySet().stream()
                .filter(entry -> entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]*"))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .filter(EnvSecretSourceTest::isWhitelistable)
                .sorted()
                .findFirst();
        assumeTrue(candidate.isPresent(), "no usable environment variable available in this JVM");
        return candidate.get();
    }

    /**
     * The blacklist refuses the application's own variables, so a fixture must not pick one.
     * Asking {@link EnvSecretSource} itself keeps this in sync with the blacklist for free.
     */
    private static boolean isWhitelistable(String name) {
        try {
            source(name);
            return true;
        } catch (IllegalStateException blacklisted) {
            return false;
        }
    }

    private static EnvSecretSource source(String... whitelist) {
        return new EnvSecretSource(properties(List.of(whitelist)));
    }

    private static SecretProperties properties(List<String> whitelist) {
        SecretProperties properties = new SecretProperties();
        properties.getEnv().setWhitelist(whitelist);
        return properties;
    }
}
