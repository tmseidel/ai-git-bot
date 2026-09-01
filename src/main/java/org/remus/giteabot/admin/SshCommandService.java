package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.SshEndpoint;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Generates client keys and scans SSH server host keys with OpenSSH tooling. */
@Slf4j
@Service
public class SshCommandService {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /** Scans the host referenced by a Gitea SSH clone URL. */
    public HostKeyScan scanHostKeys(String sshCloneUrl) {
        SshEndpoint endpoint = parseEndpoint(sshCloneUrl);
        ProcessBuilder process = new ProcessBuilder("ssh-keyscan", "-T", "10", "-p",
                Integer.toString(endpoint.port()), endpoint.host());
        return parseHostKeyScan(endpoint, run(process, 30));
    }

    /** Generates an unencrypted Ed25519 key pair for non-interactive Git operations. */
    public SshKeyPair generateKeyPair(String comment) {
        Path directory = null;
        RuntimeException failure = null;
        try {
            directory = Files.createTempDirectory("gitbot-ssh-key-");
            Path privateKey = directory.resolve("id_ed25519");
            String safeComment = comment.replace('\r', ' ').replace('\n', ' ');
            ProcessBuilder process = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519",
                    "-N", "", "-C", safeComment, "-f", privateKey.toString());
            run(process, 15);
            return new SshKeyPair(Files.readString(privateKey), Files.readString(Path.of(privateKey + ".pub")).trim());
        } catch (IOException e) {
            failure = new IllegalStateException("Failed to generate SSH key pair", e);
            throw failure;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                deleteKeyFiles(directory);
            } catch (RuntimeException cleanupError) {
                if (failure == null) {
                    throw cleanupError;
                }
                failure.addSuppressed(cleanupError);
            }
        }
    }

    static SshEndpoint parseEndpoint(String cloneUrl) {
        return SshEndpoint.parse(cloneUrl);
    }

    static HostKeyScan parseHostKeyScan(SshEndpoint endpoint, String output) {
        List<String> lines = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .filter(SshCommandService::isHostKeyLine)
                .distinct()
                .sorted()
                .toList();
        if (lines.isEmpty()) {
            throw new IllegalStateException("No SSH host keys were returned by " + endpoint.host());
        }
        String knownHosts = String.join("\n", lines) + "\n";
        List<HostKeyFingerprint> fingerprints = lines.stream()
                .map(SshCommandService::fingerprint)
                .toList();
        return new HostKeyScan(endpoint, knownHosts, fingerprints,
                sha256(endpoint.host() + ":" + endpoint.port() + "\n" + knownHosts));
    }

    private static boolean isHostKeyLine(String line) {
        String[] fields = line.split("\\s+");
        return fields.length >= 3
                && (fields[1].startsWith("ssh-") || fields[1].startsWith("ecdsa-")
                    || fields[1].startsWith("sk-"));
    }

    private static HostKeyFingerprint fingerprint(String knownHostLine) {
        String[] fields = knownHostLine.split("\\s+");
        byte[] key = Base64.getDecoder().decode(fields[2]);
        return new HostKeyFingerprint(fields[1], "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(digest(key)));
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String run(ProcessBuilder process, long timeoutSeconds) {
        process.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(process);
        try {
            ProcessSupport.CommandResult result = ProcessSupport.run(
                    process, timeoutSeconds, TimeUnit.SECONDS, MAX_OUTPUT_BYTES);
            if (!result.finished()) {
                throw new IllegalStateException("SSH command timed out");
            }
            if (result.truncated()) {
                throw new IllegalStateException("SSH command produced too much output");
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException("SSH command failed: " + result.output().trim());
            }
            return result.output();
        } catch (IOException e) {
            throw new IllegalStateException("OpenSSH tooling is unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SSH command was interrupted", e);
        }
    }

    static void deleteKeyFiles(Path directory) {
        if (directory == null) {
            return;
        }
        IOException failure = null;
        for (Path path : List.of(directory.resolve("id_ed25519"),
                directory.resolve("id_ed25519.pub"), directory)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Failed to delete temporary SSH key path {}: {}", path, e.getMessage());
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to delete temporary SSH key files", failure);
        }
    }

    /** SHA-256 fingerprint of one scanned SSH host key. */
    public record HostKeyFingerprint(String algorithm, String fingerprint) {
    }

    /** Canonical known_hosts data and the confirmation bound to that scan. */
    public record HostKeyScan(SshEndpoint endpoint, String knownHosts,
                              List<HostKeyFingerprint> fingerprints, String confirmation) {
    }

    /** Generated private and public OpenSSH key material. */
    public record SshKeyPair(String privateKey, String publicKey) {
    }
}
