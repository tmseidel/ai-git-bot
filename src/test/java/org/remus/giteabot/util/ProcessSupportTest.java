package org.remus.giteabot.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessSupportTest {

    @Test
    void scrubEnvironment_removesUnexpectedVariables() {
        ProcessBuilder processBuilder = new ProcessBuilder("unused-command");
        processBuilder.environment().put("APP_ENCRYPTION_KEY", "should-not-be-inherited");
        processBuilder.environment().put("UNRELATED_SECRET", "should-not-be-inherited");

        ProcessSupport.scrubEnvironment(processBuilder);

        assertThat(processBuilder.environment())
                .doesNotContainKeys("APP_ENCRYPTION_KEY", "UNRELATED_SECRET");
    }

    @Test
    void waitFor_enforcesTimeoutWhileOutputPipeRemainsOpen() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"),
                SlowOutputProcess.class.getName())
                .redirectErrorStream(true)
                .start();

        ProcessSupport.CommandResult result = ProcessSupport.waitFor(process, 2, TimeUnit.SECONDS, 1024);

        assertThat(result.finished()).isFalse();
        assertThat(result.output()).contains("started");
    }

    @Test
    void waitFor_terminatesBackgroundChildAfterSuccessfulParentExit(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path heartbeat = tempDir.resolve("heartbeat");
        ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"),
                BackgroundChildProcess.class.getName(), heartbeat.toString())
                .redirectErrorStream(true);

        ProcessSupport.CommandResult result = ProcessSupport.runInNewProcessGroup(processBuilder,
                5, TimeUnit.SECONDS, 1024);

        assertThat(result.finished()).isTrue();
        assertThat(result.output()).isEqualTo("started");
        String heartbeatAtCompletion = Files.readString(heartbeat);
        Thread.sleep(250);
        assertThat(Files.readString(heartbeat)).isEqualTo(heartbeatAtCompletion);
    }

    @Test
    void waitFor_boundsOutputWithoutSplittingUtf8Characters() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), UnicodeOutputProcess.class.getName())
                .redirectErrorStream(true)
                .start();

        ProcessSupport.CommandResult result = ProcessSupport.waitFor(process, 5, TimeUnit.SECONDS, 10);

        assertThat(result.output()).isEqualTo("€€€");
        assertThat(result.output().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(10);
    }

    public static final class SlowOutputProcess {
        public static void main(String[] args) throws Exception {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), SlowOutputChild.class.getName())
                    .inheritIO()
                    .start();
            System.out.print("started");
            System.out.flush();
            Thread.sleep(10_000);
        }
    }

    public static final class SlowOutputChild {
        public static void main(String[] args) throws InterruptedException {
            while (true) {
                Thread.sleep(1_000);
            }
        }
    }

    public static final class BackgroundChildProcess {
        public static void main(String[] args) throws Exception {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), BackgroundHeartbeatChild.class.getName(),
                    args[0])
                    .inheritIO()
                    .start();
            Path heartbeat = Path.of(args[0]);
            for (int attempt = 0; attempt < 100 && !Files.exists(heartbeat); attempt++) {
                Thread.sleep(10);
            }
            System.out.print("started");
            System.out.flush();
        }
    }

    public static final class BackgroundHeartbeatChild {
        public static void main(String[] args) throws Exception {
            Path heartbeat = Path.of(args[0]);
            while (true) {
                Files.writeString(heartbeat, Long.toString(System.nanoTime()));
                Thread.sleep(20);
            }
        }
    }

    public static final class UnicodeOutputProcess {
        public static void main(String[] args) {
            System.out.print("€€€€");
        }
    }
}
