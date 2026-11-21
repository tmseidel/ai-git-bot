package org.remus.giteabot.agent.filetransfer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.util.unit.DataSize;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTPS round trips against a local server. The test resolver maps the allowlisted names to
 * loopback, which is exactly what the guard refuses unless private addresses are allowed — so the
 * same server proves both the happy path and the address check.
 */
class FileTransferServiceTest {

    private static final String HOST = "assets.example.test";
    private static final String OTHER_HOST = "other.example.test";
    private static final char[] PASSWORD = "changeit".toCharArray();

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    @TempDir
    static Path keyDir;
    @TempDir
    Path repo;
    @TempDir
    Path outside;

    private HttpsServer server;
    private int port;
    private final Map<String, byte[]> received = new ConcurrentHashMap<>();
    private final byte[] png = pngBytes(4096);

    @BeforeAll
    static void createCertificate() throws Exception {
        Path keystore = keyDir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=" + HOST, "-ext", "san=dns:" + HOST + ",dns:" + OTHER_HOST,
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        keytool.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASSWORD);
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/image.png", ex -> respond(ex, 200, png, true));
        server.createContext("/text.txt", ex -> respond(ex, 200, "plain text".getBytes(StandardCharsets.UTF_8), true));
        server.createContext("/big.bin", ex -> respond(ex, 200, new byte[64 * 1024], false));
        server.createContext("/missing", ex -> respond(ex, 404, new byte[0], true));
        server.createContext("/redirect-ok", ex -> redirect(ex, "/image.png"));
        server.createContext("/redirect-other", ex -> redirect(ex, "https://evil.example.test:" + port + "/image.png"));
        server.createContext("/redirect-loop", ex -> redirect(ex, "/redirect-loop"));
        server.createContext("/upload", ex -> {
            received.put(ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("Content-Type"),
                    ex.getRequestBody().readAllBytes());
            respond(ex, 200, new byte[0], true);
        });
        server.createContext("/upload-redirect-other", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Location", "https://" + OTHER_HOST + ":" + port + "/upload");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        git("init", "-q");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- download

    @Test
    void downloadStoresByteIdenticalFileAndReportsItsSha256() throws Exception {
        ToolResult result = service(config()).download(workspace(), List.of(url("/image.png"), "assets/01.png"));

        assertThat(result.success()).as(result.error()).isTrue();
        Path stored = repo.resolve("assets/01.png");
        assertThat(Files.readAllBytes(stored)).isEqualTo(png);
        assertThat(result.output()).contains("assets/01.png", "4,096 bytes", "image/png", sha256(png));
        try (Stream<Path> files = Files.list(stored.getParent())) {
            assertThat(files).containsExactly(stored);
        }
    }

    @Test
    void downloadRecordsProvenanceWithoutTheQueryString() throws Exception {
        Workspace workspace = workspace();
        service(config()).download(workspace, List.of(url("/image.png?X-Amz-Signature=s3cr3t"), "assets/01.png"));
        workspace.recordAssetSource("other.png", "unused", "0");

        String message = workspace.withAssetSourceTrailers("feat: assets");

        assertThat(message).contains("Asset-Source: assets/01.png https://" + HOST + ":" + port
                + "/image.png sha256:" + sha256(png));
        assertThat(message).doesNotContain("s3cr3t").doesNotContain("other.png");
    }

    @Test
    void emptyHostAllowlistRefusesAndNamesTheProperty() {
        AgentConfigProperties config = config();
        config.getFileTransfer().getDownload().setAllowedHosts(List.of());

        ToolResult result = service(config).download(workspace(), List.of(url("/image.png"), "a.png"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("agent.file-transfer.download.allowed-hosts");
    }

    @Test
    void privateAddressIsRefusedUnlessAllowed() {
        AgentConfigProperties config = config();
        config.getFileTransfer().setAllowPrivateAddresses(false);

        ToolResult result = service(config).download(workspace(), List.of(url("/image.png"), "a.png"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("internal address");
        assertThat(repo.resolve("a.png")).doesNotExist();
    }

    @Test
    void connectionGoesToTheAddressTheGuardValidated() {
        // DNS rebinding needs a second lookup between check and connect. There is none: the guard
        // is the connection's own resolver, so the one answer it judges is the one connected to.
        int[] calls = {0};
        FileTransferGuard.HostResolver rebinding = host -> {
            calls[0]++;
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        };
        AgentConfigProperties config = config();
        config.getFileTransfer().setAllowPrivateAddresses(false);

        ToolResult result = new FileTransferService(config, rebinding, clientContext)
                .download(workspace(), List.of(url("/image.png"), "a.png"));

        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.error()).contains("internal address");
    }

    @Test
    void schemeOtherThanHttpsIsRefused() {
        ToolResult result = service(config()).download(workspace(),
                List.of("http://" + HOST + ":" + port + "/image.png", "a.png"));

        assertThat(result.error()).contains("Only https");
    }

    @Test
    void redirectWithinAllowedHostsIsFollowed() throws Exception {
        ToolResult result = service(config()).download(workspace(), List.of(url("/redirect-ok"), "a.png"));

        assertThat(result.success()).as(result.error()).isTrue();
        assertThat(Files.readAllBytes(repo.resolve("a.png"))).isEqualTo(png);
    }

    @Test
    void redirectToDisallowedHostIsRefused() {
        ToolResult result = service(config()).download(workspace(), List.of(url("/redirect-other"), "a.png"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("evil.example.test");
        assertThat(repo.resolve("a.png")).doesNotExist();
    }

    @Test
    void redirectLimitIsEnforced() {
        ToolResult result = service(config()).download(workspace(), List.of(url("/redirect-loop"), "a.png"));

        assertThat(result.error()).contains("Too many redirects");
    }

    @Test
    void oversizeDownloadIsAbortedAndLeavesNoPartialFile() throws Exception {
        AgentConfigProperties config = config();
        config.getFileTransfer().getDownload().setMaxFileSize(DataSize.ofKilobytes(16));

        ToolResult result = service(config).download(workspace(), List.of(url("/big.bin"), "assets/big.bin"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("larger than the allowed");
        try (Stream<Path> files = Files.list(repo.resolve("assets"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void targetPathMustStayInsideTheWorkspaceAndOutsideGit() {
        FileTransferService service = service(config());

        assertThat(service.download(workspace(), List.of(url("/image.png"), "../escape.png")).error())
                .contains("traversal");
        assertThat(service.download(workspace(), List.of(url("/image.png"), ".git/hooks/pre-commit")).error())
                .contains(".git");
        assertThat(service.download(workspace(), List.of(url("/image.png"), "/tmp/abs.png")).error())
                .contains("relative");
    }

    @Test
    void symlinkedTargetDirectoryIsRefused() throws Exception {
        Files.createSymbolicLink(repo.resolve("assets"), outside);

        ToolResult result = service(config()).download(workspace(), List.of(url("/image.png"), "assets/a.png"));

        assertThat(result.success()).isFalse();
        assertThat(outside.resolve("a.png")).doesNotExist();
    }

    @Test
    void targetPathMustMatchTheAllowedPaths() {
        AgentConfigProperties config = config();
        config.getFileTransfer().getDownload().setAllowedPaths(List.of("assets/**"));

        ToolResult result = service(config).download(workspace(), List.of(url("/image.png"), "src/a.png"));

        assertThat(result.error()).contains("agent.file-transfer.download.allowed-paths");
    }

    @Test
    void contentTypeIsCheckedFromTheBytes() {
        AgentConfigProperties config = config();
        config.getFileTransfer().getDownload().setAllowedContentTypes(List.of("image/png"));

        ToolResult result = service(config).download(workspace(), List.of(url("/text.txt"), "assets/fake.png"));

        assertThat(result.error()).contains("text/plain");
        assertThat(repo.resolve("assets/fake.png")).doesNotExist();
    }

    @Test
    void existingFileNeedsOverwrite() throws Exception {
        Files.writeString(repo.resolve("a.png"), "old");
        FileTransferService service = service(config());

        assertThat(service.download(workspace(), List.of(url("/image.png"), "a.png")).error()).contains("overwrite");
        assertThat(service.download(workspace(), List.of(url("/image.png"), "a.png", "true")).success()).isTrue();
        assertThat(Files.readAllBytes(repo.resolve("a.png"))).isEqualTo(png);
    }

    @Test
    void failureMessagesNeverContainTheQueryString() {
        ToolResult result = service(config()).download(workspace(),
                List.of(url("/missing?X-Amz-Signature=s3cr3t"), "a.png"));

        assertThat(result.error()).contains("HTTP 404").doesNotContain("s3cr3t");
    }

    // ---------------------------------------------------------------- upload

    @Test
    void uploadSendsTheFileWithItsDetectedType() throws Exception {
        Files.createDirectories(repo.resolve("inputs"));
        Files.write(repo.resolve("inputs/ref.png"), png);

        ToolResult result = service(config()).upload(workspace(),
                List.of("inputs/ref.png", url("/upload?sig=s3cr3t")));

        assertThat(result.success()).as(result.error()).isTrue();
        assertThat(result.output()).contains("HTTP 200").doesNotContain("s3cr3t");
        assertThat(received.get("PUT image/png")).isEqualTo(png);
    }

    @Test
    void uploadHonoursMethodAndContentType() throws Exception {
        Files.createDirectories(repo.resolve("inputs"));
        Files.write(repo.resolve("inputs/ref.png"), png);

        ToolResult result = service(config()).upload(workspace(),
                List.of("inputs/ref.png", url("/upload"), "POST", "application/octet-stream"));

        assertThat(result.success()).as(result.error()).isTrue();
        assertThat(received).containsKey("POST application/octet-stream");
    }

    @Test
    void uploadWithoutPathAllowlistIsRefused() throws Exception {
        AgentConfigProperties config = config();
        config.getFileTransfer().getUpload().setAllowedPaths(List.of());
        Files.writeString(repo.resolve("secret.txt"), "x");

        ToolResult result = service(config).upload(workspace(), List.of("secret.txt", url("/upload")));

        assertThat(result.error()).contains("agent.file-transfer.upload.allowed-paths");
        assertThat(received).isEmpty();
    }

    @Test
    void uploadWithoutHostAllowlistIsRefused() throws Exception {
        AgentConfigProperties config = config();
        config.getFileTransfer().getUpload().setAllowedHosts(List.of());
        Files.createDirectories(repo.resolve("inputs"));
        Files.write(repo.resolve("inputs/ref.png"), png);

        ToolResult result = service(config).upload(workspace(), List.of("inputs/ref.png", url("/upload")));

        assertThat(result.error()).contains("agent.file-transfer.upload.allowed-hosts");
    }

    @Test
    void uploadSourceMustMatchTheAllowedPaths() throws Exception {
        Files.writeString(repo.resolve("application.properties"), "password=x");

        ToolResult result = service(config()).upload(workspace(), List.of("application.properties", url("/upload")));

        assertThat(result.error()).contains("agent.file-transfer.upload.allowed-paths");
        assertThat(received).isEmpty();
    }

    @Test
    void ignoredFilesAreNeverUploaded() throws Exception {
        Files.writeString(repo.resolve(".gitignore"), "inputs/*.env\n");
        Files.createDirectories(repo.resolve("inputs"));
        Files.writeString(repo.resolve("inputs/prod.env"), "TOKEN=x");

        ToolResult result = service(config()).upload(workspace(), List.of("inputs/prod.env", url("/upload")));

        assertThat(result.error()).contains(".gitignore");
        assertThat(received).isEmpty();
    }

    @Test
    void gitInternalsAreNeverUploaded() {
        AgentConfigProperties config = config();
        config.getFileTransfer().getUpload().setAllowedPaths(List.of("**"));

        ToolResult result = service(config).upload(workspace(), List.of(".git/config", url("/upload")));

        assertThat(result.error()).contains(".git");
        assertThat(received).isEmpty();
    }

    @Test
    void oversizeUploadIsRefused() throws Exception {
        AgentConfigProperties config = config();
        config.getFileTransfer().getUpload().setMaxFileSize(DataSize.ofBytes(100));
        Files.createDirectories(repo.resolve("inputs"));
        Files.write(repo.resolve("inputs/ref.png"), png);

        ToolResult result = service(config).upload(workspace(), List.of("inputs/ref.png", url("/upload")));

        assertThat(result.error()).contains("more than the allowed");
        assertThat(received).isEmpty();
    }

    @Test
    void uploadRedirectToAnotherHostIsRefused() throws Exception {
        AgentConfigProperties config = config();
        config.getFileTransfer().getUpload().setAllowedHosts(List.of(HOST, OTHER_HOST));
        Files.createDirectories(repo.resolve("inputs"));
        Files.write(repo.resolve("inputs/ref.png"), png);

        ToolResult result = service(config).upload(workspace(), List.of("inputs/ref.png", url("/upload-redirect-other")));

        assertThat(result.error()).contains("another host");
        assertThat(received).isEmpty();
    }

    // ---------------------------------------------------------------- file-info

    @Test
    void fileInfoReturnsSizeTypeAndSha256() throws Exception {
        Files.write(repo.resolve("a.png"), png);

        ToolResult result = service(config()).fileInfo(repo, List.of("a.png"));

        assertThat(result.output()).isEqualTo("a.png: 4096 bytes, image/png, sha256 " + sha256(png));
    }

    // ---------------------------------------------------------------- helpers

    private AgentConfigProperties config() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getValidation().setToolTimeoutSeconds(10);
        AgentConfigProperties.FileTransferConfig transfer = config.getFileTransfer();
        transfer.setAllowPrivateAddresses(true);
        transfer.getDownload().setAllowedHosts(List.of(HOST));
        transfer.getUpload().setAllowedHosts(List.of(HOST));
        transfer.getUpload().setAllowedPaths(List.of("inputs/**"));
        return config;
    }

    private FileTransferService service(AgentConfigProperties config) {
        FileTransferGuard.HostResolver resolver = host -> {
            if (HOST.equals(host) || OTHER_HOST.equals(host) || "evil.example.test".equals(host)) {
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }
            throw new java.net.UnknownHostException(host);
        };
        return new FileTransferService(config, resolver, clientContext);
    }

    private Workspace workspace() {
        return Workspace.unmanaged(repo);
    }

    private String url(String path) {
        return "https://" + HOST + ":" + port + path;
    }

    private void git(String... args) throws Exception {
        String[] command = Stream.concat(Stream.of("git"), Stream.of(args)).toArray(String[]::new);
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, boolean fixedLength) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : fixedLength ? body.length : 0);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // the client may hang up on an oversized body
            }
        }
        exchange.close();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static byte[] pngBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
