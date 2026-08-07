package org.remus.giteabot.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for spawning external tool processes safely.
 *
 * <p>The application spawns build/test toolchains (mvn, npm, pytest, …) whose
 * arguments - and transitively whose build scripts - can be influenced by
 * untrusted pull-request content. Such child processes must never inherit
 * secrets from the application environment (database credentials, encryption
 * keys, OAuth client secrets). {@link #scrubEnvironment(ProcessBuilder)}
 * replaces the child environment with a minimal allowlist.</p>
 */
public final class ProcessSupport {

    /**
     * Environment variables a child process may legitimately need to run a
     * build toolchain. Everything else (especially all credentials) is
     * dropped.
     */
    private static final List<String> CHILD_ENV_ALLOWLIST = List.of(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "TMPDIR", "TMP", "TEMP",
            "JAVA_HOME", "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME",
            "NODE_PATH", "NPM_CONFIG_PREFIX",
            "PLAYWRIGHT_BROWSERS_PATH", "CYPRESS_CACHE_FOLDER", "DOTNET_ROOT",
            // Windows essentials (git/build tools fail without these)
            "SystemRoot", "windir", "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "PUBLIC", "ProgramData", "PSModulePath"
    );

    private ProcessSupport() {
    }

    /**
     * Returns a copy of the current environment restricted to the allowlist
     * (variables that are absent are skipped).
     */
    public static Map<String, String> scrubbedEnvironment() {
        Map<String, String> system = System.getenv();
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : CHILD_ENV_ALLOWLIST) {
            String value = system.get(name);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * Replaces the {@link ProcessBuilder}'s environment with the scrubbed
     * allowlist. Callers may add explicit entries afterwards (e.g. per-run
     * non-secret configuration).
     */
    public static void scrubEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(scrubbedEnvironment());
    }

    /**
     * Replaces all occurrences of the given secret values in {@code text}
     * with {@code ***}. Values shorter than 8 characters are ignored (too
     * many false positives). Intended to sanitize captured command output
     * before it is logged, sent to the LLM, or posted to a public comment.
     */
    public static String redactSecrets(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null) {
            return text;
        }
        String out = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 8) {
                out = out.replace(secret, "***");
            }
        }
        return out;
    }
}
