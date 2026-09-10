package org.remus.giteabot.repository;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Host and port parsed from an SSH URI or SCP-style Git remote. */
public record SshEndpoint(String host, int port) {

    private static final Pattern SCP_REMOTE = Pattern.compile(
            "^(?:([^@:/\\\\]+)@)?(\\[[^\\[\\]]+\\]|[^\\[\\]:/\\\\]+):(.+)$");

    public SshEndpoint {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host == null || host.isBlank() || !host.equals(host.trim())
                || host.startsWith("-") || host.equals(".") || host.equals("..")
                || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                        || c == '/' || c == '\\' || c == '@' || c == ',' || c == '*' || c == '?' || c == '!'
                        || c == '[' || c == ']')
                || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid SSH endpoint");
        }
        host = host.toLowerCase(Locale.ROOT);
    }

    /** Parses a complete {@code ssh://} or SCP-style Git remote. */
    public static SshEndpoint parse(String remote) {
        if (remote == null || remote.isBlank() || !remote.equals(remote.trim())
                || remote.startsWith("-")
                || remote.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw invalidRemote(null);
        }
        if (remote.startsWith("ssh://")) {
            return parseSshUri(remote);
        }
        if (remote.contains("://") || remote.regionMatches(true, 0, "file:", 0, 5)
                || remote.regionMatches(true, 0, "http:", 0, 5)
                || remote.regionMatches(true, 0, "https:", 0, 6)
                || remote.matches("^[A-Za-z]:.*")) {
            throw invalidRemote(null);
        }
        Matcher match = SCP_REMOTE.matcher(remote);
        if (!match.matches()) {
            throw invalidRemote(null);
        }
        String user = match.group(1);
        if ((user != null && (user.isBlank() || user.startsWith("-")))
                || match.group(3).startsWith(":")) {
            throw invalidRemote(null);
        }
        return new SshEndpoint(match.group(2), 22);
    }

    /** Returns whether the value is a complete SSH Git remote. */
    public static boolean isSshRemote(String remote) {
        try {
            parse(remote);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Returns the endpoint exposed by canonical {@code known_hosts} entries.
     * Hashed, wildcard, negated, malformed, or multi-endpoint files are left to
     * OpenSSH's strict host-key checking.
     */
    public static Optional<SshEndpoint> fromKnownHosts(String knownHosts) {
        if (knownHosts == null || knownHosts.isBlank()) {
            return Optional.empty();
        }
        LinkedHashSet<SshEndpoint> endpoints = new LinkedHashSet<>();
        for (String rawLine : knownHosts.lines().toList()) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            int hostIndex = fields[0].startsWith("@") ? 1 : 0;
            if (fields.length <= hostIndex + 1) {
                return Optional.empty();
            }
            for (String hostPattern : fields[hostIndex].split(",")) {
                if (hostPattern.startsWith("|") || hostPattern.startsWith("!")
                        || hostPattern.contains("*") || hostPattern.contains("?")) {
                    return Optional.empty();
                }
                Optional<SshEndpoint> endpoint = parseKnownHost(hostPattern);
                if (endpoint.isEmpty()) {
                    return Optional.empty();
                }
                endpoints.add(endpoint.get());
            }
        }
        return endpoints.size() == 1 ? Optional.of(endpoints.getFirst()) : Optional.empty();
    }

    /** Compares endpoints using normalized, case-insensitive host names. */
    public boolean matches(SshEndpoint other) {
        return other != null && port == other.port && host.equalsIgnoreCase(other.host);
    }

    private static SshEndpoint parseSshUri(String remote) {
        try {
            URI uri = URI.create(remote);
            String user = uri.getUserInfo();
            String path = uri.getRawPath();
            if (!"ssh".equals(uri.getScheme()) || uri.getHost() == null
                    || user != null && (user.isBlank() || user.startsWith("-") || user.contains(":")
                        || user.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
                    || path == null || path.isBlank() || path.equals("/")
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidRemote(null);
            }
            return new SshEndpoint(uri.getHost(), uri.getPort() < 0 ? 22 : uri.getPort());
        } catch (IllegalArgumentException e) {
            throw invalidRemote(e);
        }
    }

    private static Optional<SshEndpoint> parseKnownHost(String value) {
        try {
            if (value.startsWith("[")) {
                int closingBracket = value.lastIndexOf(']');
                if (closingBracket < 2 || closingBracket + 2 >= value.length()
                        || value.charAt(closingBracket + 1) != ':') {
                    return Optional.empty();
                }
                return Optional.of(new SshEndpoint(value.substring(1, closingBracket),
                        Integer.parseInt(value.substring(closingBracket + 2))));
            }
            int firstColon = value.indexOf(':');
            if (firstColon >= 0 && firstColon == value.lastIndexOf(':')) {
                return Optional.empty();
            }
            return Optional.of(new SshEndpoint(value, 22));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static IllegalArgumentException invalidRemote(Exception cause) {
        return new IllegalArgumentException("Invalid SSH Git remote", cause);
    }
}
