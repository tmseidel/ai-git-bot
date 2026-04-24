package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Executes external tools (e.g. build / test commands) requested by the AI agent.
 * <p>
 * The list of allowed tools is configured via
 * {@link AgentConfigProperties.ValidationConfig#getAvailableTools()}.
 */
@Slf4j
@Service
public class ToolExecutionService {

    private static final int MAX_TOOL_OUTPUT_CHARS = 10_000;
    private static final int MAX_SEARCH_MATCHES = 200;
    private static final int MAX_SEARCH_DEPTH = 12;
    private static final int MAX_TREE_DEPTH = 6;
    private static final int DEFAULT_GIT_LOG_LIMIT = 10;
    private static final long MAX_TEXT_FILE_SIZE_BYTES = 1_000_000;
    private static final List<String> AVAILABLE_CONTEXT_TOOLS = List.of(
            "rg", "ripgrep", "grep", "find", "cat", "git-log", "git-blame", "tree");

    private final AgentConfigProperties agentConfig;

    public ToolExecutionService(AgentConfigProperties agentConfig) {
        this.agentConfig = agentConfig;
    }

    /**
     * Returns the list of tools that the AI agent is allowed to invoke.
     */
    public List<String> getAvailableTools() {
        return agentConfig.getValidation().getAvailableTools();
    }

    /**
     * Returns the repository exploration tools the AI can use before coding.
     */
    public List<String> getAvailableContextTools() {
        return AVAILABLE_CONTEXT_TOOLS;
    }

    /**
     * Executes a tool command in the given workspace directory.
     *
     * @param workspaceDir The workspace directory
     * @param tool         The tool to execute (must be in {@link #getAvailableTools()})
     * @param arguments    The arguments to pass to the tool
     * @return The execution result
     */
    public ToolResult executeTool(Path workspaceDir, String tool, List<String> arguments) {
        List<String> availableTools = getAvailableTools();
        if (!availableTools.contains(tool)) {
            return new ToolResult(false, -1,
                    "Tool '" + tool + "' is not available. Available tools: "
                            + String.join(", ", availableTools),
                    "");
        }

        String[] command = new String[1 + (arguments != null ? arguments.size() : 0)];
        command[0] = tool;
        if (arguments != null) {
            for (int i = 0; i < arguments.size(); i++) {
                command[i + 1] = arguments.get(i);
            }
        }

        log.info("Executing tool: {} {}", tool,
                arguments != null ? String.join(" ", arguments) : "");

        return executeCommand(workspaceDir, command);
    }

    /**
     * Executes a read-only repository exploration tool in the given workspace.
     */
    public ToolResult executeContextTool(Path workspaceDir, String tool, List<String> arguments) {
        String normalizedTool = tool != null ? tool.strip().toLowerCase() : "";
        if (!AVAILABLE_CONTEXT_TOOLS.contains(normalizedTool)) {
            return new ToolResult(false, -1, "",
                    "Repository tool '" + tool + "' is not available. Available tools: "
                            + String.join(", ", AVAILABLE_CONTEXT_TOOLS));
        }

        return switch (normalizedTool) {
            // Support both names because models often ask for either `rg` or `ripgrep`.
            case "rg", "ripgrep", "grep" -> executeSearchTool(workspaceDir, arguments);
            case "find" -> executeFindTool(workspaceDir, arguments);
            case "cat" -> executeCatTool(workspaceDir, arguments);
            case "git-log" -> executeGitLogTool(workspaceDir, arguments);
            case "git-blame" -> executeGitBlameTool(workspaceDir, arguments);
            case "tree" -> executeTreeTool(workspaceDir, arguments);
            default -> new ToolResult(false, -1, "",
                    "Repository tool '" + tool + "' is not implemented");
        };
    }

    private ToolResult executeSearchTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "Search tool requires at least a pattern argument");
        }

        String patternText = arguments.getFirst();
        String relativePath = arguments.size() > 1 ? arguments.get(1) : ".";
        Path basePath;
        try {
            basePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }

        if (!Files.exists(basePath)) {
            return new ToolResult(false, 1, "", "Path not found: " + relativePath);
        }

        Pattern compiledPattern;
        try {
            compiledPattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            compiledPattern = Pattern.compile(Pattern.quote(patternText));
        }
        final Pattern pattern = compiledPattern;

        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(basePath, MAX_SEARCH_DEPTH)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isReasonableTextFile(path))
                    .sorted()
                    .toList();

            AtomicBoolean limitReached = new AtomicBoolean(false);
            for (Path file : files) {
                AtomicInteger lineNumber = new AtomicInteger(0);
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    lines.forEachOrdered(line -> {
                        int currentLine = lineNumber.incrementAndGet();
                        if (!limitReached.get() && pattern.matcher(line).find()) {
                            matches.add(workspaceDir.relativize(file) + ":" + currentLine + ": " + line);
                            if (matches.size() >= MAX_SEARCH_MATCHES) {
                                limitReached.set(true);
                            }
                        }
                    });
                }
                if (limitReached.get()) {
                    return new ToolResult(true, 0, truncateOutput(String.join("\n", matches)), "");
                }
            }
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "Search failed: " + e.getMessage());
        }

        String output = matches.isEmpty()
                ? "No matches found for pattern: " + patternText
                : String.join("\n", matches);
        return new ToolResult(true, 0, truncateOutput(output), "");
    }

    private ToolResult executeFindTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "find requires a glob pattern");
        }

        String globPattern = arguments.getFirst();
        String relativePath = arguments.size() > 1 ? arguments.get(1) : ".";
        Path basePath;
        try {
            basePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }

        if (!Files.exists(basePath)) {
            return new ToolResult(false, 1, "", "Path not found: " + relativePath);
        }

        try (Stream<Path> stream = Files.walk(basePath)) {
            List<String> matches = stream
                    .filter(path -> !path.equals(basePath))
                    .sorted()
                    .map(workspaceDir::relativize)
                    .map(Path::toString)
                    .filter(path -> matchesGlob(path, globPattern))
                    .toList();

            String output = matches.isEmpty()
                    ? "No files found for pattern: " + globPattern
                    : String.join("\n", matches);
            return new ToolResult(true, 0, truncateOutput(output), "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "find failed: " + e.getMessage());
        }
    }

    private ToolResult executeCatTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "cat requires a file path");
        }

        String relativePath = arguments.getFirst();
        int startLine = 1;
        int endLine = Integer.MAX_VALUE;
        try {
            if (arguments.size() > 1) {
                startLine = Integer.parseInt(arguments.get(1));
            }
            if (arguments.size() > 2) {
                endLine = Integer.parseInt(arguments.get(2));
            }
        } catch (NumberFormatException e) {
            return new ToolResult(false, -1, "", "cat line arguments must be integers");
        }

        if (startLine < 1 || endLine < startLine) {
            return new ToolResult(false, -1, "", "Invalid line range for cat");
        }

        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }

        if (!Files.isRegularFile(filePath)) {
            return new ToolResult(false, 1, "", "File not found: " + relativePath);
        }

        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int lastLine = Math.min(endLine, lines.size());
            StringBuilder output = new StringBuilder();
            for (int i = startLine; i <= lastLine; i++) {
                output.append(String.format("%d | %s%n", i, lines.get(i - 1)));
            }
            if (output.isEmpty()) {
                output.append("No content in requested line range.");
            }
            return new ToolResult(true, 0, truncateOutput(output.toString().stripTrailing()), "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "cat failed: " + e.getMessage());
        }
    }

    private ToolResult executeGitLogTool(Path workspaceDir, List<String> arguments) {
        String relativePath = null;
        int limit = DEFAULT_GIT_LOG_LIMIT;
        if (arguments != null && !arguments.isEmpty()) {
            if (arguments.size() == 1 && isInteger(arguments.getFirst())) {
                limit = Integer.parseInt(arguments.getFirst());
            } else {
                relativePath = arguments.getFirst();
                if (arguments.size() > 1 && isInteger(arguments.get(1))) {
                    limit = Integer.parseInt(arguments.get(1));
                }
            }
        }

        List<String> command = new ArrayList<>(List.of(
                "git", "log", "--date=short", "--pretty=format:%h %ad %an %s", "-n", String.valueOf(limit)));
        if (relativePath != null && !relativePath.isBlank()) {
            try {
                resolveWorkspacePath(workspaceDir, relativePath);
            } catch (IOException e) {
                return new ToolResult(false, -1, "", e.getMessage());
            }
            command.add("--");
            command.add(relativePath);
        }
        return executeCommand(workspaceDir, command.toArray(String[]::new));
    }

    private ToolResult executeGitBlameTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "git-blame requires a file path");
        }

        String relativePath = arguments.getFirst();
        try {
            resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }

        List<String> command = new ArrayList<>(List.of("git", "blame"));
        if (arguments.size() > 2 && isInteger(arguments.get(1)) && isInteger(arguments.get(2))) {
            command.add("-L");
            command.add(arguments.get(1) + "," + arguments.get(2));
        }
        command.add("--");
        command.add(relativePath);
        return executeCommand(workspaceDir, command.toArray(String[]::new));
    }

    private ToolResult executeTreeTool(Path workspaceDir, List<String> arguments) {
        String relativePath = ".";
        int maxDepth = 3;
        if (arguments != null && !arguments.isEmpty()) {
            if (arguments.size() == 1 && isInteger(arguments.getFirst())) {
                maxDepth = Integer.parseInt(arguments.getFirst());
            } else {
                relativePath = arguments.getFirst();
                if (arguments.size() > 1 && isInteger(arguments.get(1))) {
                    maxDepth = Integer.parseInt(arguments.get(1));
                }
            }
        }
        maxDepth = Math.min(Math.max(maxDepth, 1), MAX_TREE_DEPTH);

        Path basePath;
        try {
            basePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }

        if (!Files.exists(basePath)) {
            return new ToolResult(false, 1, "", "Path not found: " + relativePath);
        }

        try (Stream<Path> stream = Files.walk(basePath, maxDepth)) {
            List<String> lines = stream
                    .sorted(Comparator.naturalOrder())
                    .map(path -> formatTreeEntry(basePath, path))
                    .toList();
            return new ToolResult(true, 0, truncateOutput(String.join("\n", lines)), "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "tree failed: " + e.getMessage());
        }
    }

    private String formatTreeEntry(Path basePath, Path path) {
        Path relative = basePath.relativize(path);
        int depth = relative.getNameCount();
        String indent = depth <= 1 ? "" : "  ".repeat(depth - 1);
        String name = depth == 0 ? basePath.getFileName().toString() : relative.getFileName().toString();
        if (Files.isDirectory(path)) {
            name += "/";
        }
        return indent + name;
    }

    private boolean matchesGlob(String path, String globPattern) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedPattern = globPattern.replace('\\', '/');
        if (!normalizedPattern.contains("/")) {
            return Path.of(normalizedPath).getFileName().toString().matches(globToRegex(normalizedPattern));
        }
        return normalizedPath.matches(globToRegex(normalizedPattern));
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.' -> regex.append("\\.");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return regex.toString();
    }

    private boolean isReasonableTextFile(Path path) {
        try {
            return Files.size(path) <= MAX_TEXT_FILE_SIZE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Path resolveWorkspacePath(Path workspaceDir, String relativePath) throws IOException {
        Path resolved = workspaceDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceDir.normalize())) {
            throw new IOException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    private ToolResult executeCommand(Path workspaceDir, String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int timeoutSeconds = agentConfig.getValidation().getToolTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, -1, "",
                        "Tool execution timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            log.info("Tool {} with exit code {}",
                    success ? "succeeded" : "failed", exitCode);

            return new ToolResult(success, exitCode, truncateOutput(output.toString()), "");

        } catch (IOException e) {
            log.error("Failed to execute tool: {}", e.getMessage());
            return new ToolResult(false, -1, "",
                    "Failed to execute tool: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, -1, "",
                    "Tool execution interrupted");
        }
    }

    private String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_TOOL_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n... (output truncated)";
    }
}
