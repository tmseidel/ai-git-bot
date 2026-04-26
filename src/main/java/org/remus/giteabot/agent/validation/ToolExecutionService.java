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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    private static final Pattern SIMPLE_BRANCH_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._\\-/]+");
    // Includes branch-switcher although it mutates workspace state; it is still executed in the
    // context phase because it affects which branch subsequent context reads should target.
    private static final List<String> AVAILABLE_CONTEXT_TOOLS = List.of(
            "rg", "ripgrep", "grep", "find", "cat", "git-log", "git-blame", "tree", "branch-switcher");
    /** File-modification tools — run in the workspace but results are NOT posted as public comments. */
    private static final List<String> AVAILABLE_FILE_TOOLS = List.of(
            "write-file", "patch-file", "mkdir", "delete-file");

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
     * Returns the file-modification tools the AI can use to create/patch/delete files
     * in the workspace. Results go back to the AI but are NOT posted as public comments.
     */
    public List<String> getAvailableFileTools() {
        return AVAILABLE_FILE_TOOLS;
    }

    /**
     * Returns {@code true} if the given tool name belongs to the read-only context tools
     * (e.g. {@code cat}, {@code rg}) whose results should not be posted as issue comments.
     */
    public boolean isContextTool(String tool) {
        return AVAILABLE_CONTEXT_TOOLS.contains(tool != null ? tool.strip().toLowerCase() : "");
    }

    /**
     * Returns {@code true} if the given tool is a file-modification tool
     * (write-file, patch-file, mkdir, delete-file).
     */
    public boolean isFileTool(String tool) {
        return AVAILABLE_FILE_TOOLS.contains(tool != null ? tool.strip().toLowerCase() : "");
    }

    /**
     * Returns {@code true} if the tool result should NOT be posted as a public issue comment.
     * Both context tools and file tools are "silent".
     */
    public boolean isSilentTool(String tool) {
        return isContextTool(tool) || isFileTool(tool);
    }
    /**
     * Returns {@code true} if the given tool is a configured validation tool
     * (i.e. listed in {@link AgentConfigProperties.ValidationConfig#getAvailableTools()},
     * e.g. {@code mvn}, {@code gradle}, {@code npm}).
     * <p>
     * This is the authoritative check for "does this tool count as validation?".
     * Using {@code !isSilentTool} is <em>not</em> equivalent: a tool could be unknown
     * to all three categories, and silently falling into the "validation" bucket would
     * produce incorrect pass/fail semantics.
     */
    public boolean isValidationTool(String tool) {
        return getAvailableTools().contains(tool != null ? tool.strip().toLowerCase() : "");
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
            case "rg", "ripgrep", "grep" -> executeSearchTool(workspaceDir, normalizedTool, arguments);
            case "find" -> executeFindTool(workspaceDir, arguments);
            case "cat" -> executeCatTool(workspaceDir, arguments);
            case "git-log" -> executeGitLogTool(workspaceDir, arguments);
            case "git-blame" -> executeGitBlameTool(workspaceDir, arguments);
            case "tree" -> executeTreeTool(workspaceDir, arguments);
            case "branch-switcher" -> executeBranchSwitcherTool(workspaceDir, arguments);
            default -> new ToolResult(false, -1, "",
                    "Repository tool '" + tool + "' is not implemented");
        };
    }

    private ToolResult executeBranchSwitcherTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "branch-switcher requires a branch name argument");
        }

        String firstArg = arguments.getFirst();
        String requested = firstArg != null ? firstArg.strip() : "";
        String branch = normalizeBranchName(requested);
        if (branch == null || branch.isBlank()) {
            return new ToolResult(false, -1, "", "branch-switcher requires a non-empty branch name");
        }
        if (!SIMPLE_BRANCH_NAME_PATTERN.matcher(branch).matches()) {
            return new ToolResult(false, -1, "", "Invalid branch name");
        }

        ToolResult refCheck = executeCommand(workspaceDir,
                new String[]{"git", "check-ref-format", "--branch", branch});
        if (!refCheck.success()) {
            return new ToolResult(false, refCheck.exitCode(), "",
                    "Invalid branch name for git: " + branch);
        }

        ToolResult fetch = executeCommand(workspaceDir,
                new String[]{"git", "fetch", "origin",
                        "refs/heads/" + branch + ":refs/remotes/origin/" + branch});
        if (!fetch.success()) {
            return new ToolResult(false, fetch.exitCode(), "",
                    "Failed to fetch branch '" + branch + "' from origin: "
                            + normalizeToolMessage(fetch.output()));
        }

        ToolResult checkout = executeCommand(workspaceDir,
                new String[]{"git", "checkout", "-B", branch, "refs/remotes/origin/" + branch});
        if (!checkout.success()) {
            return new ToolResult(false, checkout.exitCode(), "",
                    "Failed to switch to branch '" + branch + "': "
                            + normalizeToolMessage(checkout.output()));
        }

        ToolResult currentBranch = executeCommand(workspaceDir,
                new String[]{"git", "rev-parse", "--abbrev-ref", "HEAD"});
        if (!currentBranch.success()) {
            return new ToolResult(false, currentBranch.exitCode(), "",
                    "Failed to verify checked-out branch: " + normalizeToolMessage(currentBranch.output()));
        }
        String checkedOutBranch = normalizeToolMessage(currentBranch.output());
        if (!branch.equals(checkedOutBranch)) {
            return new ToolResult(false, -1, "",
                    "Branch switch verification failed");
        }

        return new ToolResult(true, 0, "Switched workspace branch to: " + checkedOutBranch, "");
    }

    private String normalizeBranchName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return null;
        }
        if (branchName.startsWith("refs/heads/")) {
            return branchName.substring("refs/heads/".length());
        }
        return branchName;
    }

    private String normalizeToolMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /**
     * Executes a file-modification tool (write-file, patch-file, mkdir, delete-file)
     * in the given workspace.  Results are passed back to the AI but never posted as
     * public issue comments.
     *
     * @param workspaceDir The workspace directory (cloned repo root)
     * @param tool         One of the {@link #AVAILABLE_FILE_TOOLS}
     * @param arguments    Tool-specific arguments
     * @return The execution result
     */
    public ToolResult executeFileTool(Path workspaceDir, String tool, List<String> arguments) {
        String normalizedTool = tool != null ? tool.strip().toLowerCase() : "";
        if (!AVAILABLE_FILE_TOOLS.contains(normalizedTool)) {
            return new ToolResult(false, -1, "",
                    "File tool '" + tool + "' is not available. Available file tools: "
                            + String.join(", ", AVAILABLE_FILE_TOOLS));
        }
        return switch (normalizedTool) {
            case "write-file" -> executeWriteFileTool(workspaceDir, arguments);
            case "patch-file" -> executePatchFileTool(workspaceDir, arguments);
            case "mkdir"      -> executeMkdirTool(workspaceDir, arguments);
            case "delete-file" -> executeDeleteFileTool(workspaceDir, arguments);
            default -> new ToolResult(false, -1, "", "File tool '" + tool + "' is not implemented");
        };
    }

    private ToolResult executeWriteFileTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.size() < 2) {
            return new ToolResult(false, -1, "", "write-file requires two arguments: <path> <content>");
        }
        String relativePath = arguments.get(0);
        String content      = arguments.get(1);
        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("write-file: wrote {} bytes to {}", content.length(), relativePath);
            return new ToolResult(true, 0, "File written: " + relativePath, "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "write-file failed: " + e.getMessage());
        }
    }

    private ToolResult executePatchFileTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.size() < 3) {
            return new ToolResult(false, -1, "",
                    "patch-file requires three arguments: <path> <search-text> <replacement-text>");
        }
        String relativePath    = arguments.get(0);
        String searchText      = arguments.get(1);
        String replacementText = arguments.get(2);
        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        if (!Files.isRegularFile(filePath)) {
            return new ToolResult(false, 1, "", "patch-file: file not found: " + relativePath);
        }
        try {
            String originalContent = Files.readString(filePath, StandardCharsets.UTF_8);
            if (!originalContent.contains(searchText)) {
                return new ToolResult(false, 1, "",
                        "patch-file: search text not found in file: " + relativePath
                                + ". Use `cat` to inspect the exact current content first.");
            }
            // Count occurrences to detect ambiguous patches — replacing >1 occurrence is
            // almost always a mistake (e.g. duplicated method signatures, repeated imports).
            int occurrences = countOccurrences(originalContent, searchText);
            if (occurrences > 1) {
                return new ToolResult(false, 1, "",
                        "patch-file: search text matches " + occurrences + " locations in "
                                + relativePath + " — the replacement would be ambiguous. "
                                + "Provide a more specific search string that matches exactly once "
                                + "(use `cat` to identify a unique surrounding context).");
            }
            String newContent = originalContent.replace(searchText, replacementText);
            Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
            log.info("patch-file: patched {}", relativePath);
            return new ToolResult(true, 0, "File patched: " + relativePath, "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "patch-file failed: " + e.getMessage());
        }
    }

    /** Counts the number of non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx   = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private ToolResult executeMkdirTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "mkdir requires a path argument");
        }
        String relativePath = arguments.getFirst();
        Path dirPath;
        try {
            dirPath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        try {
            Files.createDirectories(dirPath);
            log.info("mkdir: created directory {}", relativePath);
            return new ToolResult(true, 0, "Directory created: " + relativePath, "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "mkdir failed: " + e.getMessage());
        }
    }

    private ToolResult executeDeleteFileTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "delete-file requires a path argument");
        }
        String relativePath = arguments.getFirst();
        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.info("delete-file: deleted {}", relativePath);
                return new ToolResult(true, 0, "File deleted: " + relativePath, "");
            } else {
                // Return a visible warning so the AI notices a potential path typo.
                log.warn("delete-file: file did not exist: {}", relativePath);
                return new ToolResult(true, 1,
                        "Warning: file did not exist, nothing was deleted — verify the path: "
                                + relativePath, "");
            }
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "delete-file failed: " + e.getMessage());
        }
    }

    private ToolResult executeSearchTool(Path workspaceDir, String tool, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "Search tool requires at least a pattern argument");
        }

        SearchRequest searchRequest = parseSearchRequest(arguments);
        String patternText = searchRequest.patternText();
        String relativePath = searchRequest.relativePath();
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
            compiledPattern = compileSearchPattern(patternText, searchRequest);
        } catch (PatternSyntaxException e) {
            compiledPattern = Pattern.compile(Pattern.quote(patternText), searchRequest.regexFlags());
        }

        List<String> matches;
        try {
            matches = findMatches(workspaceDir, basePath, compiledPattern, searchRequest);
            if (matches.isEmpty() && shouldRetryWithUnescapedAlternation(tool, patternText, searchRequest)) {
                String normalizedPatternText = patternText.replace("\\|", "|");
                Pattern normalizedPattern = compileSearchPattern(normalizedPatternText, searchRequest);
                matches = findMatches(workspaceDir, basePath, normalizedPattern, searchRequest);
                if (!matches.isEmpty()) {
                    patternText = normalizedPatternText;
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

    private Pattern compileSearchPattern(String patternText, SearchRequest searchRequest) {
        if (searchRequest.fixedString()) {
            return Pattern.compile(Pattern.quote(patternText), searchRequest.regexFlags());
        }
        return Pattern.compile(patternText, searchRequest.regexFlags());
    }

    private List<String> findMatches(Path workspaceDir, Path basePath,
                                     Pattern pattern, SearchRequest searchRequest) throws IOException {
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(basePath, MAX_SEARCH_DEPTH)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isReasonableTextFile)
                    .sorted()
                    .toList();

            for (Path file : files) {
                String relativeFilePath = workspaceDir.relativize(file).toString();
                if (!matchesAnyGlob(relativeFilePath, searchRequest.includeGlobs(), searchRequest.caseInsensitive())) {
                    continue;
                }

                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!pattern.matcher(line).find()) {
                        continue;
                    }
                    if (searchRequest.filesOnly()) {
                        matches.add(relativeFilePath);
                        break;
                    }
                    matches.add(relativeFilePath + ":" + (i + 1) + ": " + line);
                    if (matches.size() >= MAX_SEARCH_MATCHES) {
                        return matches;
                    }
                }
                if (searchRequest.filesOnly() && matches.size() >= MAX_SEARCH_MATCHES) {
                    return matches;
                }
            }
        }
        return matches;
    }

    private boolean shouldRetryWithUnescapedAlternation(String tool, String patternText, SearchRequest searchRequest) {
        if (searchRequest.fixedString()) {
            return false;
        }
        if (!("rg".equals(tool) || "ripgrep".equals(tool) || "grep".equals(tool))) {
            return false;
        }
        return patternText.contains("\\|");
    }

    private SearchRequest parseSearchRequest(List<String> arguments) {
        String patternText = arguments.getFirst();
        String relativePath = ".";
        Set<Character> flags = new LinkedHashSet<>();
        List<String> includeGlobs = new ArrayList<>();

        for (int i = 1; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument == null || argument.isBlank()) {
                continue;
            }
            if (argument.startsWith("--include=")) {
                includeGlobs.add(argument.substring("--include=".length()));
                continue;
            }
            if (argument.startsWith("--glob=")) {
                includeGlobs.add(argument.substring("--glob=".length()));
                continue;
            }
            if ("--glob".equals(argument) || "-g".equals(argument)) {
                if (i + 1 < arguments.size()) {
                    includeGlobs.add(arguments.get(++i));
                }
                continue;
            }
            if (argument.startsWith("-") && argument.length() > 1) {
                for (int j = 1; j < argument.length(); j++) {
                    flags.add(argument.charAt(j));
                }
                continue;
            }
            if (".".equals(relativePath)) {
                relativePath = argument;
            }
        }

        int regexFlags = flags.contains('i') ? Pattern.CASE_INSENSITIVE : 0;
        boolean fixedString = flags.contains('F');
        boolean filesOnly = flags.contains('l');
        boolean caseInsensitive = flags.contains('i');
        return new SearchRequest(patternText, relativePath, regexFlags, fixedString, filesOnly,
                caseInsensitive, includeGlobs);
    }

    private record SearchRequest(String patternText, String relativePath, int regexFlags,
                                 boolean fixedString, boolean filesOnly,
                                 boolean caseInsensitive, List<String> includeGlobs) {
    }

    private ToolResult executeFindTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "find requires a glob pattern");
        }

        FindRequest request = parseFindRequest(arguments);
        if (request.globPattern() == null || request.globPattern().isBlank()) {
            return new ToolResult(false, -1, "", "find requires a glob pattern");
        }

        String globPattern = request.globPattern();
        String relativePath = request.relativePath();
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
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(workspaceDir::relativize)
                    .map(Path::toString)
                    .filter(path -> matchesGlob(path, globPattern, request.caseInsensitive()))
                    .filter(path -> matchesAnyGlob(path, request.includeGlobs(), request.caseInsensitive()))
                    .toList();

            String output = matches.isEmpty()
                    ? "No files found for pattern: " + globPattern
                    : String.join("\n", matches);
            return new ToolResult(true, 0, truncateOutput(output), "");
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "find failed: " + e.getMessage());
        }
    }

    private FindRequest parseFindRequest(List<String> arguments) {
        String relativePath = ".";
        String globPattern = null;
        boolean caseInsensitive = false;
        List<String> includeGlobs = new ArrayList<>();
        List<String> positionalArgs = new ArrayList<>();

        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument == null || argument.isBlank()) {
                continue;
            }
            if (argument.startsWith("--include=")) {
                includeGlobs.add(argument.substring("--include=".length()));
                continue;
            }
            if (argument.startsWith("--glob=")) {
                includeGlobs.add(argument.substring("--glob=".length()));
                continue;
            }
            switch (argument) {
                case "--glob", "-g" -> {
                    if (i + 1 < arguments.size()) {
                        includeGlobs.add(arguments.get(++i));
                    }
                    continue;
                }
                case "-name", "-iname" -> {
                    caseInsensitive = "-iname".equals(argument);
                    if (i + 1 < arguments.size()) {
                        globPattern = arguments.get(++i);
                    }
                    continue;
                }
                case "-type" -> {
                    if (i + 1 < arguments.size()) {
                        i++;
                    }
                    continue;
                }
            }
            if (argument.startsWith("-")) {
                continue;
            }
            positionalArgs.add(argument);
        }

        if (globPattern != null) {
            if (!positionalArgs.isEmpty()) {
                relativePath = positionalArgs.getFirst();
            }
        } else if (positionalArgs.size() >= 2) {
            globPattern = positionalArgs.getFirst();
            relativePath = positionalArgs.get(1);
        } else if (positionalArgs.size() == 1) {
            globPattern = positionalArgs.getFirst();
        }

        return new FindRequest(relativePath, globPattern, caseInsensitive, includeGlobs);
    }

    private record FindRequest(String relativePath, String globPattern,
                               boolean caseInsensitive, List<String> includeGlobs) {
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
        maxDepth = Math.clamp(maxDepth, 1, MAX_TREE_DEPTH);

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

    private boolean matchesAnyGlob(String path, List<String> globPatterns, boolean caseInsensitive) {
        if (globPatterns == null || globPatterns.isEmpty()) {
            return true;
        }
        return globPatterns.stream().anyMatch(glob -> matchesGlob(path, glob, caseInsensitive));
    }

    private boolean matchesGlob(String path, String globPattern, boolean caseInsensitive) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedPattern = globPattern.replace('\\', '/');
        if (caseInsensitive) {
            normalizedPath = normalizedPath.toLowerCase();
            normalizedPattern = normalizedPattern.toLowerCase();
        }
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
        // Stage 1: normalize() resolves any ".." segments without touching the filesystem.
        Path normalized = workspaceDir.resolve(relativePath).normalize();
        if (!normalized.startsWith(workspaceDir.normalize())) {
            throw new IOException("Path escapes workspace: " + relativePath);
        }
        // Stage 2: if the target already exists, re-check after symlink resolution so that
        // a symlink inside the workspace pointing outside is also caught.
        if (Files.exists(normalized)) {
            Path realBase = workspaceDir.toRealPath();
            Path realPath = normalized.toRealPath();
            if (!realPath.startsWith(realBase)) {
                throw new IOException("Path escapes workspace via symlink: " + relativePath);
            }
        }
        return normalized;
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
