package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.util.ProcessSupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} in a hardened environment: repository-controlled hooks and fsmonitor are
 * disabled, and neither the system nor the user's global configuration is read.
 */
@Slf4j
final class GitCommandRunner {

    private GitCommandRunner() {
    }

    static CommandResult run(File workDir, String[] command, int timeoutSeconds) {
        return run(workDir, command, timeoutSeconds, Map.of());
    }

    static CommandResult run(File workDir, String[] command, int timeoutSeconds,
                             Map<String, String> extraEnvironment) {
        Path disabledHooksDirectory = null;
        Path emptyGlobalGitConfig = null;
        try {
            // Git reads repository-controlled configuration after untrusted code ran in the workspace.
            disabledHooksDirectory = Files.createTempDirectory("ai-git-bot-empty-hooks-");
            emptyGlobalGitConfig = Files.createTempFile(disabledHooksDirectory, "global-", ".gitconfig");
            List<String> gitCommand = new ArrayList<>(command.length + 6);
            gitCommand.add(command[0]);
            gitCommand.add("-c");
            gitCommand.add("core.hooksPath=" + disabledHooksDirectory.toAbsolutePath().normalize());
            gitCommand.add("-c");
            gitCommand.add("core.fsmonitor=false");
            gitCommand.add("-c");
            gitCommand.add("credential.helper=");
            gitCommand.addAll(Arrays.asList(command).subList(1, command.length));
            ProcessBuilder pb = new ProcessBuilder(gitCommand);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironmentForGit(pb);
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalGitConfig.toString());
            pb.environment().putAll(extraEnvironment);

            ProcessSupport.CommandResult result = ProcessSupport.run(
                    pb, timeoutSeconds, TimeUnit.SECONDS, 1024 * 1024);
            if (!result.finished()) {
                return new CommandResult(false, "git " + gitSubcommand(command)
                        + " timed out after " + timeoutSeconds + " seconds");
            }

            return new CommandResult(result.exitCode() == 0, result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command was interrupted", e);
        } catch (IOException e) {
            log.error("Failed to run command: {}", e.getMessage());
            return new CommandResult(false, "Exception: " + e.getMessage());
        } finally {
            if (emptyGlobalGitConfig != null) {
                try {
                    Files.deleteIfExists(emptyGlobalGitConfig);
                } catch (IOException e) {
                    log.warn("Failed to remove empty global Git config {}: {}",
                            emptyGlobalGitConfig, e.getMessage());
                }
            }
            if (disabledHooksDirectory != null) {
                try {
                    Files.deleteIfExists(disabledHooksDirectory);
                } catch (IOException e) {
                    log.warn("Failed to remove empty Git hooks directory {}: {}",
                            disabledHooksDirectory, e.getMessage());
                }
            }
        }
    }

    /**
     * Returns the git subcommand of a command line that may carry {@code -c}
     * {@code key=value} pairs in front of it, so a timeout names the operation
     * that ran out of budget instead of just its duration.
     */
    private static String gitSubcommand(String[] command) {
        for (int i = 1; i < command.length; i++) {
            if (!"-c".equals(command[i])) {
                return command[i];
            }
            i++; // skip the configuration value that follows -c
        }
        return command[0];
    }
}
