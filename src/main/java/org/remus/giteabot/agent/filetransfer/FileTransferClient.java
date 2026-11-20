package org.remus.giteabot.agent.filetransfer;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.util.Timeout;
import org.remus.giteabot.util.UrlRedaction;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * HTTPS transfers. Redirects are followed by hand so every hop is re-checked; DNS goes through
 * {@link FileTransferGuard#resolve}, which pins the connection to the validated addresses.
 */
@Slf4j
public class FileTransferClient {

    private static final String USER_AGENT = "ai-git-bot-file-transfer";

    private final FileTransferGuard guard;
    private final SSLContext sslContext;
    private final int maxRedirects;
    private final int timeoutSeconds;

    public FileTransferClient(FileTransferGuard guard, SSLContext sslContext, int maxRedirects, int timeoutSeconds) {
        this.guard = guard;
        this.sslContext = sslContext;
        this.maxRedirects = maxRedirects;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record Downloaded(URI finalUri, long size, String sha256) { }

    public Downloaded download(URI uri, List<String> allowedHosts, String property, long maxBytes, Path target) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        URI current = uri;
        try (CloseableHttpClient client = newClient()) {
            for (int hop = 0; ; hop++) {
                URI requested = current;
                Object outcome;
                HttpGet get = new HttpGet(requested);
                // Closing the response discards the connection. Closing the body stream, or a
                // response handler, would first drain the rest of an oversized body.
                try (ClassicHttpResponse response = client.executeOpen(null, get, null)) {
                    int status = response.getCode();
                    if (isRedirect(status)) {
                        outcome = location(requested, response.getFirstHeader("Location"));
                    } else if (status < 200 || status >= 300) {
                        throw new FileTransferException("Download failed with HTTP " + status
                                + " from " + UrlRedaction.redact(requested.toString()));
                    } else {
                        HttpEntity entity = response.getEntity();
                        if (entity == null) {
                            throw new FileTransferException("Download returned no body");
                        }
                        if (entity.getContentLength() > maxBytes) {
                            throw new FileTransferException("Download is larger than the allowed "
                                    + maxBytes + " bytes");
                        }
                        try (OutputStream out = Files.newOutputStream(target)) {
                            outcome = copyCapped(entity.getContent(), out, maxBytes, deadline, requested);
                        } catch (IOException | RuntimeException e) {
                            get.cancel();
                            throw e;
                        }
                    }
                }
                if (outcome instanceof URI next) {
                    if (hop >= maxRedirects) {
                        throw new FileTransferException("Too many redirects (max " + maxRedirects + ")");
                    }
                    current = guard.checkUri(next, allowedHosts, property);
                    continue;
                }
                Streamed streamed = (Streamed) outcome;
                return new Downloaded(requested, streamed.size(), streamed.sha256());
            }
        } catch (IOException e) {
            throw new FileTransferException("Download failed: " + UrlRedaction.redactAll(e.getMessage()));
        }
    }

    /** Follows only 307/308 to the same host, so a body is never replayed elsewhere. */
    public int upload(URI uri, String method, String contentType, Path file,
                      List<String> allowedHosts, String property) {
        URI current = uri;
        try (CloseableHttpClient client = newClient()) {
            for (int hop = 0; ; hop++) {
                URI requested = current;
                HttpUriRequestBase request = "POST".equals(method) ? new HttpPost(requested) : new HttpPut(requested);
                request.setEntity(new FileEntity(file.toFile(), ContentType.parse(contentType)));
                Object outcome;
                try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
                    int status = response.getCode();
                    outcome = status == 307 || status == 308
                            ? location(requested, response.getFirstHeader("Location"))
                            : status;
                }
                if (outcome instanceof URI next) {
                    if (hop >= maxRedirects) {
                        throw new FileTransferException("Too many redirects (max " + maxRedirects + ")");
                    }
                    if (next.getHost() == null || !next.getHost().equalsIgnoreCase(requested.getHost())) {
                        throw new FileTransferException("Upload redirect to another host refused: "
                                + UrlRedaction.redact(next.toString()));
                    }
                    current = guard.checkUri(next, allowedHosts, property);
                    continue;
                }
                return (Integer) outcome;
            }
        } catch (IOException e) {
            throw new FileTransferException("Upload failed: " + UrlRedaction.redactAll(e.getMessage()));
        }
    }

    private CloseableHttpClient newClient() {
        Timeout timeout = Timeout.ofSeconds(timeoutSeconds);
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return guard.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(timeout)
                                .setSocketTimeout(timeout)
                                .build())
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent(USER_AGENT)
                .build();
    }

    private record Streamed(long size, String sha256) { }

    private Streamed copyCapped(InputStream in, OutputStream out, long maxBytes, long deadline, URI source)
            throws IOException {
        MessageDigest digest = BinaryFiles.newDigest();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new FileTransferException("Download is larger than the allowed " + maxBytes + " bytes");
            }
            if (System.nanoTime() > deadline) {
                throw new FileTransferException("Download timed out after " + timeoutSeconds + " seconds: "
                        + UrlRedaction.redact(source.toString()));
            }
            digest.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
        return new Streamed(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI location(URI base, Header header) {
        if (header == null || header.getValue() == null || header.getValue().isBlank()) {
            throw new FileTransferException("Redirect without a Location header");
        }
        try {
            return base.resolve(header.getValue().strip());
        } catch (IllegalArgumentException e) {
            throw new FileTransferException("Invalid redirect location");
        }
    }

    static String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "PUT" : method.strip().toUpperCase(Locale.ROOT);
    }
}
