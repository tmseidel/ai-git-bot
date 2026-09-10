package org.remus.giteabot.secret;

/**
 * A secret that a {@link SecretSource} resolved, together with the reference it came from.
 *
 * @param value      the resolved secret - never rendered by {@link #toString()}
 * @param sourceType the {@link SecretSource#type()} that resolved this secret
 * @param key        the key this secret was resolved from
 */
public record SecretValue(String value, String sourceType, String key) {

    /** Renders the reference only; the resolved secret must never reach a log or an error message. */
    @Override
    public String toString() {
        return "SecretValue(sourceType=" + sourceType + ", key=" + key + ")";
    }
}
