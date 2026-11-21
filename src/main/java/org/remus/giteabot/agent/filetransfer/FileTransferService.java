package org.remus.giteabot.agent.filetransfer;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.AgentConfigProperties.FileTransferConfig;
import org.remus.giteabot.util.UrlRedaction;
import org.remus.giteabot.util.WorkspacePaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The {@code download-file}, {@code upload-file} and {@code file-info} tools. */
@Slf4j
@Service
public class FileTransferService {

    static final String DOWNLOAD_HOSTS = "agent.file-transfer.download.allowed-hosts";
    static final String DOWNLOAD_PATHS = "agent.file-transfer.download.allowed-paths";
    static final String DOWNLOAD_TYPES = "agent.file-transfer.download.allowed-content-types";
    static final String UPLOAD_HOSTS = "agent.file-transfer.upload.allowed-hosts";
    static final String UPLOAD_PATHS = "agent.file-transfer.upload.allowed-paths";

    private static final Pattern CONTENT_TYPE = Pattern.compile("[\\w.+-]+/[\\w.+-]+(\\s*;[^\\r\\n]*)?");

    private final AgentConfigProperties agentConfig;
    private final FileTransferGuard guard;
    private final FileTransferClient client;

    @Autowired
    public FileTransferService(AgentConfigProperties agentConfig) {
        this(agentConfig, InetAddress::getAllByName, defaultSslContext());
    }

    public FileTransferService(AgentConfigProperties agentConfig, FileTransferGuard.HostResolver resolver,
                               SSLContext sslContext) {
        this.agentConfig = agentConfig;
        FileTransferConfig config = agentConfig.getFileTransfer();
        this.guard = new FileTransferGuard(config.isAllowPrivateAddresses(), resolver);
        this.client = new FileTransferClient(guard, sslContext, config.getMaxRedirects(), agentConfig.getValidation().getToolTimeoutSeconds());
    }

    public ToolResult download(Workspace workspace, List<String> arguments) {
        if (arguments == null || arguments.size() < 2 || isBlank(arguments.get(0)) || isBlank(arguments.get(1))) {
            return failure("download-file requires two arguments: <url> <path>");
        }
        String url = arguments.get(0);
        String relativePath = normalize(arguments.get(1));
        boolean overwrite = arguments.size() > 2 && "true".equalsIgnoreCase(arguments.get(2).strip());
        FileTransferConfig.DownloadConfig download = config().getDownload();
        Path temp = null;
        try {
            URI uri = guard.checkUrl(url, download.getAllowedHosts(), DOWNLOAD_HOSTS);
            Path target = resolve(workspace.path(), relativePath);
            if (!FileTransferGuard.pathMatches(relativePath, download.getAllowedPaths())) {
                return failure("Path '" + relativePath + "' is not in " + DOWNLOAD_PATHS);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return failure("Target exists and is not a regular file: " + relativePath);
                }
                if (!overwrite) {
                    return failure("File already exists: " + relativePath + ". Pass overwrite=true to replace it.");
                }
            }
            Path parent = target.getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".download-", ".part");
            FileTransferClient.Downloaded result = client.download(uri, download.getAllowedHosts(), DOWNLOAD_HOSTS,
                    download.getMaxFileSize().toBytes(), temp);
            String mimeType = BinaryFiles.detectMimeType(temp);
            if (hasEntries(download.getAllowedContentTypes())
                    && download.getAllowedContentTypes().stream().noneMatch(t -> t.strip().equalsIgnoreCase(mimeType))) {
                return failure("Downloaded content is " + mimeType + ", which is not in " + DOWNLOAD_TYPES);
            }
            target = resolve(workspace.path(), relativePath);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            String source = UrlRedaction.redact(result.finalUri().toString());
            workspace.recordAssetSource(relativePath, source, result.sha256());
            log.info("download-file: host={} path={} size={} type={} sha256={}",
                    result.finalUri().getHost(), relativePath, result.size(), mimeType, result.sha256());
            return new ToolResult(true, 0, "Downloaded " + relativePath + " (" + formatBytes(result.size())
                    + ", " + mimeType + ", sha256 " + result.sha256() + ")", "");
        } catch (FileTransferException | IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (IOException e) {
            return failure("download-file failed: " + UrlRedaction.redactAll(e.getMessage()));
        } finally {
            deleteQuietly(temp);
        }
    }

    public ToolResult upload(Workspace workspace, List<String> arguments) {
        if (arguments == null || arguments.size() < 2 || isBlank(arguments.get(0)) || isBlank(arguments.get(1))) {
            return failure("upload-file requires two arguments: <path> <url>");
        }
        String relativePath = normalize(arguments.get(0));
        String url = arguments.get(1);
        String method = FileTransferClient.normalizeMethod(arguments.size() > 2 ? arguments.get(2) : null);
        String requestedType = arguments.size() > 3 ? arguments.get(3) : null;
        FileTransferConfig.UploadConfig upload = config().getUpload();
        try {
            URI uri = guard.checkUrl(url, upload.getAllowedHosts(), UPLOAD_HOSTS);
            if (!hasEntries(upload.getAllowedPaths())) {
                return failure("No path may be uploaded: configure " + UPLOAD_PATHS + " to enable this tool");
            }
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                return failure("method must be PUT or POST");
            }
            Path source = resolve(workspace.path(), relativePath);
            if (!FileTransferGuard.pathMatches(relativePath, upload.getAllowedPaths())) {
                return failure("Path '" + relativePath + "' is not in " + UPLOAD_PATHS);
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return failure("File not found: " + relativePath);
            }
            if (workspace.isIgnored(relativePath)) {
                return failure("Path '" + relativePath + "' is ignored by .gitignore and cannot be uploaded");
            }
            long size = Files.size(source);
            long max = upload.getMaxFileSize().toBytes();
            if (size > max) {
                return failure("File is " + formatBytes(size) + ", more than the allowed " + formatBytes(max));
            }
            BinaryFiles.Info info = BinaryFiles.describe(source);
            String contentType = isBlank(requestedType) ? info.mimeType() : requestedType.strip();
            if (!CONTENT_TYPE.matcher(contentType).matches()) {
                return failure("Invalid contentType: " + contentType);
            }
            int status = client.upload(uri, method, contentType, source, upload.getAllowedHosts(), UPLOAD_HOSTS);
            log.info("upload-file: host={} path={} size={} sha256={} status={}",
                    uri.getHost(), relativePath, info.size(), info.sha256(), status);
            String summary = "HTTP " + status + " for " + method + " " + UrlRedaction.redact(uri.toString())
                    + " (" + relativePath + ", " + formatBytes(info.size()) + ", " + contentType + ")";
            return status >= 200 && status < 300
                    ? new ToolResult(true, 0, "Uploaded: " + summary, "")
                    : failure("Upload rejected: " + summary);
        } catch (FileTransferException | IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (IOException e) {
            return failure("upload-file failed: " + e.getMessage());
        }
    }

    public ToolResult fileInfo(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty() || isBlank(arguments.getFirst())) {
            return failure("file-info requires a path argument");
        }
        String relativePath = normalize(arguments.getFirst());
        try {
            Path file = resolve(workspaceDir, relativePath);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return new ToolResult(false, 1, "", "File not found: " + relativePath);
            }
            BinaryFiles.Info info = BinaryFiles.describe(file);
            return new ToolResult(true, 0, relativePath + ": " + info.size() + " bytes, "
                    + info.mimeType() + ", sha256 " + info.sha256(), "");
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (IOException e) {
            return failure("file-info failed: " + e.getMessage());
        }
    }

    private FileTransferConfig config() {
        return agentConfig.getFileTransfer();
    }

    private static Path resolve(Path workspaceDir, String relativePath) {
        return WorkspacePaths.resolveInsideWorkspace(workspaceDir, relativePath);
    }

    private static String normalize(String relativePath) {
        String path = relativePath.strip().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private static String formatBytes(long bytes) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(bytes) + " bytes";
    }

    private static boolean hasEntries(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ToolResult failure(String message) {
        return new ToolResult(false, -1, "", UrlRedaction.redactAll(message));
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete partial download {}: {}", file, e.getMessage());
        }
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No default SSLContext", e);
        }
    }
}
