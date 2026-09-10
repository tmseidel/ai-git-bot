package org.remus.giteabot.secret;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${env:NAME}} references against the environment of the running process.
 * <p>
 * Only the names configured via {@code giteabot.secret.env.whitelist} are readable, so a
 * bot configuration cannot exfiltrate arbitrary environment variables of the host.
 */
@Slf4j
@Component
public class EnvSecretSource implements SecretSource {

    private static final Pattern ENV_VALIDATION_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** Environment variables the application itself reads; a {@code ${env:NAME}} reference may never name one. */
    private static final List<Pattern> BLACKLIST = List.of(
            Pattern.compile("GITEABOT_.*"),
            Pattern.compile("SPRING_.*"),
            Pattern.compile("DATABASE_.*"),
            Pattern.compile("APP_ENCRYPTION_KEY"));

    private final List<String> envVarWhitelist;

    public EnvSecretSource(SecretProperties secretProperties) {
        this.envVarWhitelist = secretProperties.getEnv().getWhitelist().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct().toList();

        List<String> blacklisted = this.envVarWhitelist.stream()
                .filter(this::isBlacklisted)
                .sorted()
                .toList();

        if (!blacklisted.isEmpty()) {
            throw new IllegalStateException("""
                    giteabot.secret.env.whitelist must not contain environment variables the application
                    itself uses: %s. Put these secrets in a differently named variable.""".formatted(blacklisted));
        }

        log.debug("Environment secret source configured with whitelist: {}", String.join(",", envVarWhitelist));
    }

    private boolean isBlacklisted(String key) {
        return BLACKLIST.stream().anyMatch(pattern -> pattern.matcher(key).matches());
    }

    @Override
    public Optional<SecretValue> resolve(String key) {

        if (key == null) {
            throw new KeyResolveException("Key should not be empty.");
        }

        if (!ENV_VALIDATION_PATTERN.matcher(key).matches()) {
            throw new KeyResolveException("The name of an environment variable must start with a letter (a-z) or underscore (_), and may contain numbers (0-9) and underscores (_). Got \"%s\" instead.".formatted(key));
        }

        if (isBlacklisted(key)) {
            throw new KeyResolveException("\"%s\" can not be used as a key, since the application itself already uses it.".formatted(key));
        }

        if (!envVarWhitelist.contains(key)) {
            log.debug("Environment variable \"{}\" is not listed in giteabot.secret.env.whitelist", key);
            return Optional.empty();
        }

        // no case normalization to upper case for env variable names
        // since Linux is case-sensitive, windows is not. So we just take the user input verbatim.
        // export a=1 A=2 && sh -c 'echo $a $A'
        // -> 1 2

        String env = System.getenv(key);
        if (env == null) {
            log.debug("Whitelisted environment variable \"{}\" is not set", key);
            return Optional.empty();
        }

        return Optional.of(new SecretValue(env, type(), key));
    }

    @Override
    public String type() {
        return "env";
    }
}
