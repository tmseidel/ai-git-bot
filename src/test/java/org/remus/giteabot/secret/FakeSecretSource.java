package org.remus.giteabot.secret;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * In-memory {@link SecretSource} for tests.
 * <p>
 * Either resolves from a fixed map or - when constructed via {@link #echoing(String)} -
 * echoes the requested key back, which lets a test observe exactly which key the
 * parser extracted from a {@code ${type:key}} reference.
 */
public class FakeSecretSource implements SecretSource {

    private final String type;
    private final Function<String, Optional<String>> resolver;
    private final Map<String, Integer> resolveCalls = new HashMap<>();

    private FakeSecretSource(String type, Function<String, Optional<String>> resolver) {
        this.type = type;
        this.resolver = resolver;
    }

    /** Resolves only the given key/value pairs, everything else stays unresolved. */
    public static FakeSecretSource of(String type, Map<String, String> values) {
        return new FakeSecretSource(type, key -> Optional.ofNullable(values.get(key)));
    }

    /** Resolves every key to {@code [key]}, so the resolved value reveals the parsed key. */
    public static FakeSecretSource echoing(String type) {
        return new FakeSecretSource(type, key -> Optional.of("[" + key + "]"));
    }

    /** Never resolves anything. */
    public static FakeSecretSource empty(String type) {
        return new FakeSecretSource(type, key -> Optional.empty());
    }

    /** Always fails, to verify that resolve errors are not swallowed. */
    public static FakeSecretSource failing(String type) {
        return new FakeSecretSource(type, key -> {
            throw new KeyResolveException("boom: " + key);
        });
    }

    @Override
    public Optional<SecretValue> resolve(String key) {
        resolveCalls.merge(key, 1, Integer::sum);
        return resolver.apply(key).map(value -> new SecretValue(value, type, key));
    }

    @Override
    public String type() {
        return type;
    }

    public int resolveCalls(String key) {
        return resolveCalls.getOrDefault(key, 0);
    }
}
