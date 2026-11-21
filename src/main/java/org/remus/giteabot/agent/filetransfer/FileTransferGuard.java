package org.remus.giteabot.agent.filetransfer;

import org.remus.giteabot.util.UrlRedaction;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Scheme, host, address and path checks for file transfers. {@link #resolve} is the HTTP client's
 * DNS resolver, so connections go to exactly the addresses validated here.
 */
public class FileTransferGuard {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateAddresses;
    private final HostResolver resolver;

    public FileTransferGuard(boolean allowPrivateAddresses, HostResolver resolver) {
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.resolver = resolver;
    }

    public URI checkUrl(String url, List<String> allowedHosts, String property) {
        if (allowedHosts == null || allowedHosts.stream().allMatch(h -> h == null || h.isBlank())) {
            throw new FileTransferException("No host is allowed: configure " + property + " to enable this tool");
        }
        if (url == null || url.isBlank()) {
            throw new FileTransferException("url must not be blank");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new FileTransferException("Invalid URL: " + UrlRedaction.redact(url));
        }
        return checkUri(uri, allowedHosts, property);
    }

    public URI checkUri(URI uri, List<String> allowedHosts, String property) {
        String shown = UrlRedaction.redact(uri.toString());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new FileTransferException("Only https URLs are allowed: " + shown);
        }
        if (uri.getRawUserInfo() != null) {
            throw new FileTransferException("URLs with user info are not allowed: " + shown);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FileTransferException("URL has no host: " + shown);
        }
        if (!hostMatches(host, allowedHosts)) {
            throw new FileTransferException("Host '" + host + "' is not in " + property);
        }
        return uri;
    }

    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        if (!allowPrivateAddresses) {
            for (InetAddress address : addresses) {
                if (isInternalAddress(address)) {
                    throw new UnknownHostException("Host '" + host + "' resolves to the internal address "
                            + address.getHostAddress() + ", which is not allowed");
                }
            }
        }
        return addresses;
    }

    static boolean hostMatches(String host, List<String> patterns) {
        String candidate = stripTrailingDot(host.toLowerCase(Locale.ROOT));
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = stripTrailingDot(raw.strip().toLowerCase(Locale.ROOT));
            if (pattern.startsWith("*.")) {
                if (candidate.endsWith(pattern.substring(1))) {
                    return true;
                }
            } else if (candidate.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean pathMatches(String path, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        Path candidate = Path.of(path).normalize();
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = raw.strip();
            if ("**".equals(pattern)
                    || FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isInternalIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            if (isIpv4Embedded(bytes)) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isInternalAddress(InetAddress.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    private static boolean isInternalIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 0 && (b[2] & 0xff) == 0
                || first == 192 && second == 168
                || first == 198 && (second == 18 || second == 19)
                || first >= 224;
    }

    private static boolean isIpv4Embedded(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        boolean mapped = (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = b[10] == 0 && b[11] == 0;
        return mapped || compatible;
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
