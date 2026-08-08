package org.remus.giteabot.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Helpers for spawning untrusted tool processes without application secrets. */
public final class ProcessSupport {

    private static final int OUTPUT_BUFFER_SIZE = 8192;

    private static final List<String> CHILD_ENV_ALLOWLIST = List.of(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "TMPDIR", "TMP", "TEMP",
            "JAVA_HOME", "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME",
            "NODE_PATH", "NPM_CONFIG_PREFIX",
            "PIP_BREAK_SYSTEM_PACKAGES",
            "PLAYWRIGHT_BROWSERS_PATH", "CYPRESS_CACHE_FOLDER", "DOTNET_ROOT",
            "SystemRoot", "windir", "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "PUBLIC", "ProgramData", "PSModulePath");

    private static final List<String> GIT_TRANSPORT_ENV_ALLOWLIST = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "SSL_CERT_FILE", "SSL_CERT_DIR");

    private ProcessSupport() {
    }

    /** Captured result of a process whose combined output was drained asynchronously. */
    public record CommandResult(boolean finished, int exitCode, String output) {
    }

    /** Replaces a child process environment with the minimal toolchain allowlist. */
    public static void scrubEnvironment(ProcessBuilder processBuilder) {
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        for (String name : CHILD_ENV_ALLOWLIST) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
    }

    /** Applies a minimal environment for Git transport without inheriting user Git configuration. */
    public static void scrubEnvironmentForGit(ProcessBuilder processBuilder) {
        scrubEnvironment(processBuilder);
        Map<String, String> environment = processBuilder.environment();
        environment.remove("HOME");
        environment.remove("USERPROFILE");
        environment.remove("HOMEDRIVE");
        environment.remove("HOMEPATH");
        environment.remove("APPDATA");
        environment.remove("LOCALAPPDATA");
        for (String name : GIT_TRANSPORT_ENV_ALLOWLIST) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
    }

    /**
     * Waits for a process while draining its output concurrently, so an open
     * output pipe cannot prevent the timeout from taking effect.
     */
    public static CommandResult waitFor(Process process, long timeout, TimeUnit unit, int maxOutputBytes)
            throws InterruptedException {
        return waitFor(process, timeout, unit, maxOutputBytes, () -> { }, true);
    }

    /**
     * Starts an untrusted process in a dedicated Linux process group so normal
     * background children cannot survive after the command completes.
     */
    public static CommandResult runInNewProcessGroup(ProcessBuilder processBuilder, long timeout, TimeUnit unit,
                                                      int maxOutputBytes) throws IOException, InterruptedException {
        if (!isLinux()) {
            throw new IOException("Direct fallback requires Linux process-group support");
        }

        List<String> originalCommand = List.copyOf(processBuilder.command());
        List<String> groupedCommand = new ArrayList<>(originalCommand.size() + 1);
        groupedCommand.add("setsid");
        groupedCommand.addAll(originalCommand);
        processBuilder.command(groupedCommand);
        try {
            Process process = processBuilder.start();
            return waitFor(process, timeout, unit, maxOutputBytes,
                    () -> terminateProcessGroup(process.pid()), false);
        } catch (IOException e) {
            processBuilder.command(originalCommand);
            throw new IOException("Direct fallback requires the setsid utility", e);
        }
    }

    private static CommandResult waitFor(Process process, long timeout, TimeUnit unit, int maxOutputBytes,
                                         Runnable processGroupCleanup, boolean trackDescendants)
            throws InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        Thread descendantTracker = null;
        if (trackDescendants) {
            captureDescendants(process, descendants);
            descendantTracker = startDescendantTracker(process, descendants);
        }
        Thread reader = startOutputReader(process, output, Math.max(0, maxOutputBytes));
        try {
            boolean finished = process.waitFor(timeout, unit);
            if (finished) {
                // A successful command must not leave build daemons running outside its lifetime.
                processGroupCleanup.run();
                captureDescendants(process, descendants);
                terminateDescendants(descendants);
                terminateDescendantsForcibly(descendants);
            } else {
                processGroupCleanup.run();
                terminateProcessTree(process, descendants);
                closeOutput(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(2_000);
            if (reader.isAlive()) {
                // A background descendant kept the inherited output pipe open.
                processGroupCleanup.run();
                terminateProcessTree(process, descendants);
                closeOutput(process);
                reader.join(2_000);
            }
            synchronized (output) {
                return new CommandResult(finished, finished ? process.exitValue() : -1,
                        decodeUtf8(output.toByteArray()));
            }
        } catch (InterruptedException e) {
            processGroupCleanup.run();
            terminateProcessTree(process, descendants);
            closeOutput(process);
            throw e;
        } finally {
            if (descendantTracker != null) {
                descendantTracker.interrupt();
            }
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static void terminateProcessGroup(long leaderPid) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("kill", "-KILL", "--", "-" + leaderPid);
            processBuilder.redirectErrorStream(true);
            scrubEnvironment(processBuilder);
            waitFor(processBuilder.start(), 5, TimeUnit.SECONDS, 64 * 1024);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String decodeUtf8(byte[] output) {
        int length = output.length;
        while (length > 0) {
            int sequenceStart = length - 1;
            while (sequenceStart >= 0 && isUtf8ContinuationByte(output[sequenceStart])) {
                sequenceStart--;
            }
            if (sequenceStart < 0) {
                return "";
            }
            int sequenceLength = utf8SequenceLength(output[sequenceStart]);
            if (sequenceLength == 0) {
                length = sequenceStart;
            } else if (length - sequenceStart >= sequenceLength) {
                return new String(output, 0, length, StandardCharsets.UTF_8);
            } else {
                length = sequenceStart;
            }
        }
        return "";
    }

    private static boolean isUtf8ContinuationByte(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private static int utf8SequenceLength(byte value) {
        int unsignedValue = Byte.toUnsignedInt(value);
        if (unsignedValue <= 0x7F) {
            return 1;
        }
        if (unsignedValue >= 0xC2 && unsignedValue <= 0xDF) {
            return 2;
        }
        if (unsignedValue >= 0xE0 && unsignedValue <= 0xEF) {
            return 3;
        }
        if (unsignedValue >= 0xF0 && unsignedValue <= 0xF4) {
            return 4;
        }
        return 0;
    }

    private static void terminateProcessTree(Process process, Set<ProcessHandle> trackedDescendants) {
        captureDescendants(process, trackedDescendants);
        terminateDescendants(trackedDescendants);
        process.destroy();
        terminateDescendantsForcibly(trackedDescendants);
        process.destroyForcibly();
    }

    private static void captureDescendants(Process process, Set<ProcessHandle> trackedDescendants) {
        trackedDescendants.addAll(process.toHandle().descendants().toList());
    }

    private static void terminateDescendants(Set<ProcessHandle> descendants) {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
    }

    private static void terminateDescendantsForcibly(Set<ProcessHandle> descendants) {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static Thread startDescendantTracker(Process process, Set<ProcessHandle> trackedDescendants) {
        Thread tracker = new Thread(() -> {
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                captureDescendants(process, trackedDescendants);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            captureDescendants(process, trackedDescendants);
        }, "process-descendant-tracker");
        tracker.setDaemon(true);
        tracker.start();
        return tracker;
    }

    private static void closeOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // The process already closed its output stream.
        }
    }

    private static Thread startOutputReader(Process process, ByteArrayOutputStream output, int maxOutputBytes) {
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[OUTPUT_BUFFER_SIZE];
            try (InputStream input = process.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (output) {
                        int remaining = maxOutputBytes - output.size();
                        if (remaining > 0) {
                            output.write(buffer, 0, Math.min(read, remaining));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The process exited while its stream was being drained.
            }
        }, "process-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }
}
