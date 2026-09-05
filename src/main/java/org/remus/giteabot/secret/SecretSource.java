package org.remus.giteabot.secret;

import java.util.Optional;

public interface SecretSource {
    /**
     * Resolves a key to a SecretValue.
     *
     * @param key The key of the secret, which should never be null.
     * @return An Optional holding a SecretValue, if the key was successfully resolved to value. Otherwise, an empty Optional.
     *
     * @throws KeyResolveException Is thrown in case: (1) the key itself is invalid for the SecretSource, (2) the unlikely case that the given key is null, (3) or if the application is not allowed to access the secret behind the key.
     */
    Optional<SecretValue> resolve(String key);

    /**
     * The lowercase type name of this secret source, like env, file, vault etc.
     *
     * @return the lowercase type name, should never return null.
     */
    String type();
}
