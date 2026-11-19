package org.remus.giteabot.agent.validation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.packagemanager.PackageManagerDetection;
import org.remus.giteabot.agent.packagemanager.PackageManagerDetector;
import org.remus.giteabot.agent.packagemanager.YarnCommandBuilder;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes external tools (e.g. build / test commands) requested by the AI agent.
 * <p>
 * Pure executor: classification of tool names lives in {@link ToolCatalog}
 * and callers that need it should inject the catalog directly. This service
 * no longer exposes {@code is*Tool} / {@code getAvailable*Tools} forwarders —
 * having two ways to ask the same question was the root cause of duplicated
 * taxonomy lists across the codebase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {

    private static final int MAX_TOOL_OUTPUT_CHARS = 10_000;
    private static final int MAX_SEARCH_MATCHES = 200;
    static final int MAX_SEARCH_MATCHES_PER_FILE = 5;
    private static final int MAX_SEARCH_DEPTH = 12;
    private static final int MAX_TREE_DEPTH = 6;
    private static final int DEFAULT_GIT_LOG_LIMIT = 10;
    private static final long MAX_TEXT_FILE_SIZE_BYTES = 1_000_000;
    private static final int BINARY_SNIFF_BYTES = 8_000;
    private static final Pattern SIMPLE_BRANCH_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._\\-/]+");

    private final AgentConfigProperties agentConfig;
    private final ToolCatalog catalog;
    private final PackageManagerDetector packageManagerDetector;
    private final YarnCommandBuilder yarnCommandBuilder;

    /**
     * Executes a configured validation tool in the given workspace:
     * either a binary from {@code PATH} (mvn, gradle, …) or, for {@code execute},
     * a script committed inside the repository. Rejects tools not in
     * {@link ToolCatalog#validationToolNames()}.
     */
    public ToolResult executeTool(Workspace workspace, String tool, List<String> arguments) {
        Path workspaceDir = workspace.path();
        List<String> availableTools = catalog.validationToolNames();
        if (!availableTools.contains(tool)) {
            return new ToolResult(false, -1,
                    "Tool '" + tool + "' is not available. Available tools: "
                            + String.join(", ", availableTools),
                    "");
        }

        return switch (tool) {
            case "execute" -> executeScriptTool(workspaceDir, arguments);
            case "npm" -> executeNpmTool(workspaceDir, arguments);
            case "yarn" -> executeYarnTool(workspaceDir, arguments);
            default -> executePathTool(workspaceDir, tool, arguments);
        };
    }

    /**
     * Runs a binary from {@code PATH} with the supplied arguments.
     */
    private ToolResult executePathTool(Path workspaceDir, String tool, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(tool);
        if (arguments != null) {
            command.addAll(arguments);
        }

        log.info("Executing tool: {} {}", tool,
                arguments != null ? String.join(" ", arguments) : "");

        return executeCommand(workspaceDir, command.toArray(String[]::new));
    }

    /**
     * npm for an npm-managed project, refused for a Yarn-managed one. The refusal is the
     * tool-layer enforcement: an npm install in a Yarn checkout ignores {@code yarn.lock}
     * and writes a {@code package-lock.json}, which the generated pull request would then
     * carry. The model's prompt says the same thing, but the prompt is not a guarantee.
     */
    private ToolResult executeNpmTool(Path workspaceDir, List<String> arguments) {
        PackageManagerDetection detection = packageManagerDetector.detect(workspaceDir);
        if (detection.isYarn()) {
            log.info("Refusing npm: the workspace is Yarn-managed ({})", detection.evidence());
            return new ToolResult(false, -1, "",
                    "This repository is managed by Yarn (" + detection.evidence() + "), so npm is "
                            + "refused here — it would ignore yarn.lock and create a package-lock.json. "
                            + "Use the `yarn` tool for installs, dependency changes and scripts. If "
                            + "`yarn` is not in your tool list, ask the operator to enable it in the "
                            + "bot's tool configuration.");
        }
        return executePathTool(workspaceDir, "npm", arguments);
    }

    /**
     * Runs Yarn with the version the checkout pins. The command comes from
     * {@link YarnCommandBuilder}, which uses Corepack when present and may install Yarn
     * Classic through npm (only when the operator allowed it). Lockfile changes land in the
     * workspace like any other file, so the commit ({@code git add -A}) and the pull request
     * carry {@code package.json} and {@code yarn.lock} together.
     */
    private ToolResult executeYarnTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "",
                    "yarn requires at least one argument, for example install, add <package>, "
                            + "add -D <package>, remove <package>, run build, test or audit.");
        }

        PackageManagerDetection detection = packageManagerDetector.detect(workspaceDir);
        YarnCommandBuilder.Invocation invocation = yarnCommandBuilder.build(detection, arguments);
        if (invocation.failed()) {
            log.info("Cannot run yarn in this workspace: {}", invocation.failure());
            return new ToolResult(false, -1, "", invocation.failure());
        }

        for (List<String> preparation : invocation.preparation()) {
            String preparedCommand = String.join(" ", preparation);
            log.info("Preparing the Yarn toolchain: {}", preparedCommand);
            ToolResult prepared = executeCommand(workspaceDir,
                    preparation.toArray(String[]::new), invocation.environment());
            if (!prepared.success()) {
                String detail = prepared.error().isBlank()
                        ? prepared.output() : prepared.error() + "\n" + prepared.output();
                return new ToolResult(false, prepared.exitCode(), "",
                        "Preparing the Yarn toolchain failed (" + preparedCommand + "): "
                                + detail.strip());
            }
        }

        log.info("Executing yarn ({}): {}", detection.evidence(), String.join(" ", arguments));
        return executeCommand(workspaceDir, invocation.command().toArray(String[]::new),
                invocation.environment());
    }

    /**
     * Runs the validation script committed inside the repository (the {@code execute}
     * tool), addressed by its repository-relative path. The path must resolve to an
     * executable regular file inside the workspace and is executed directly — never
     * through a shell — so the argument cannot smuggle additional commands. Arguments
     * after the path are forwarded verbatim; exit code {@code 0} means passed.
     */
    private ToolResult executeScriptTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()
                || arguments.getFirst() == null || arguments.getFirst().isBlank()) {
            return new ToolResult(false, -1, "",
                    "execute requires the repository-relative path to a validation script "
                            + "(for example scripts/validate.sh).");
        }

        String relativePath = arguments.getFirst().strip();
        Path script;
        try {
            script = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "Validation script rejected: " + e.getMessage());
        }
        if (!Files.isRegularFile(script)) {
            return new ToolResult(false, -1, "",
                    "Validation script not found inside the repository: " + relativePath);
        }
        if (!Files.isExecutable(script)) {
            return new ToolResult(false, -1, "",
                    "Validation script is not executable: " + relativePath
                            + ". Commit the executable bit (chmod +x / git update-index --chmod=+x) — "
                            + "the tool never falls back to a shell interpreter.");
        }

        String status = scriptStatus(workspaceDir, relativePath);
        if (status == null) {
            return new ToolResult(false, -1, "",
                    "Validation script could not be verified against the repository: " + relativePath
                            + " (git status failed — is the workspace a git checkout?)");
        }
        if (!status.isEmpty()) {
            return new ToolResult(false, -1, "",
                    "Validation script must be the committed version: " + relativePath
                            + " differs from HEAD (" + status + "). Editing the script cannot"
                            + " change the validation result.");
        }

        List<String> command = new ArrayList<>(arguments.size());
        command.add(script.toAbsolutePath().toString());
        command.addAll(arguments.subList(1, arguments.size()));

        log.info("Executing repository validation script: {}", relativePath);
        // Pre-flight checks and the launch are not atomic: the file could be swapped in
        // between, but only by something already inside the workspace — accepted.
        return executeCommand(workspaceDir, command.toArray(String[]::new));
    }

    /**
     * Porcelain status of the script: {@code ""} when it matches HEAD, non-empty when it is
     * modified, staged, untracked, deleted or ignored, {@code null} when git could not be
     * asked. {@code --ignored} matters because an ignored script never appears in a plain
     * status, yet it is just as editable by the agent.
     */
    private String scriptStatus(Path workspaceDir, String relativePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "status", "--porcelain", "--ignored", "--", relativePath);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(pb);

            ProcessSupport.CommandResult result = ProcessSupport.run(pb,
                    agentConfig.getValidation().getToolTimeoutSeconds(), TimeUnit.SECONDS, 1_000);
            return result.finished() && result.exitCode() == 0 ? result.output().strip() : null;
        } catch (IOException e) {
            log.warn("Could not read the git status of {}: {}", relativePath, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Executes a read-only repository exploration tool in the given workspace.
     */
    public ToolResult executeContextTool(Workspace workspace, String tool, List<String> arguments) {
        Path workspaceDir = workspace.path();
        String normalizedTool = tool != null ? tool.strip().toLowerCase() : "";
        if (!catalog.contextToolNames().contains(normalizedTool)) {
            return new ToolResult(false, -1, "",
                    "Repository tool '" + tool + "' is not available. Available tools: "
                            + String.join(", ", catalog.contextToolNames()));
        }

        return switch (normalizedTool) {
            // Support both names because models often ask for either `rg` or `ripgrep`.
            case "rg", "ripgrep", "grep" -> executeSearchTool(workspaceDir, normalizedTool, arguments);
            case "find" -> executeFindTool(workspaceDir, arguments);
            case "cat" -> executeCatTool(workspaceDir, arguments);
            case "git-log" -> executeGitLogTool(workspaceDir, arguments);
            case "git-blame" -> executeGitBlameTool(workspaceDir, arguments);
            case "tree" -> executeTreeTool(workspaceDir, arguments);
            case "branch-switcher" -> executeBranchSwitcherTool(workspace, arguments);
            case "ctags-signatures" -> executeCtagsSignaturesTool(workspaceDir, arguments);
            case "ctags-deps"       -> executeCtagsDepsTool(workspaceDir, arguments);
            default -> new ToolResult(false, -1, "",
                    "Repository tool '" + tool + "' is not implemented");
        };
    }

    private ToolResult executeBranchSwitcherTool(Workspace workspace, List<String> arguments) {
        Path workspaceDir = workspace.path();
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

        CommandResult fetch = workspace.fetchBranch(branch);
        if (!fetch.success()) {
            return new ToolResult(false, -1, "",
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
     * @param workspace The workspace (cloned repo root)
     * @param tool      One of {@link ToolCatalog#fileToolNames()}
     * @param arguments Tool-specific arguments
     * @return The execution result
     */
    public ToolResult executeFileTool(Workspace workspace, String tool, List<String> arguments) {
        Path workspaceDir = workspace.path();
        String normalizedTool = tool != null ? tool.strip().toLowerCase() : "";
        if (!catalog.fileToolNames().contains(normalizedTool)) {
            return new ToolResult(false, -1, "",
                    "File tool '" + tool + "' is not available. Available file tools: "
                            + String.join(", ", catalog.fileToolNames()));
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
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                String existingContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (existingContent.equals(content)) {
                    return new ToolResult(false, 1, "",
                            "write-file would not change file: " + relativePath
                                    + ". Provide different content or skip this tool.");
                }
            }
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
            PatchAttempt attempt = tryPatch(originalContent, searchText, replacementText);
            return switch (attempt.status()) {
                case OK -> {
                    Files.writeString(filePath, attempt.newContent(), StandardCharsets.UTF_8);
                    log.info("patch-file: patched {} ({})", relativePath, attempt.matchTier());
                    String hint = attempt.matchTier() == MatchTier.EXACT
                            ? ""
                            : " (matched via " + attempt.matchTier().label()
                                    + " — line endings/trailing whitespace normalized)";
                    yield new ToolResult(true, 0, "File patched: " + relativePath + hint, "");
                }
                case NOT_FOUND -> {
                    // The reason otherwise only reaches the model, so an operator reading the log
                    // could not tell which call failed. The search text itself stays at DEBUG:
                    // it is repository content.
                    log.info("patch-file: failed {} (NOT_FOUND)", relativePath);
                    log.debug("patch-file: NOT_FOUND {}: search text starts with '{}'",
                            relativePath, firstLineOf(searchText));
                    yield new ToolResult(false, 1, "",
                            "patch-file: search text not found in file: " + relativePath
                                    + ". The search was tried as-is, with normalized line endings, "
                                    + "and with whitespace-tolerant matching. Use `cat` to inspect "
                                    + "the exact current content first.");
                }
                case AMBIGUOUS -> {
                    log.info("patch-file: failed {} (AMBIGUOUS, {} matching locations)",
                            relativePath, attempt.occurrences());
                    yield new ToolResult(false, 1, "",
                            "patch-file: search text matches " + attempt.occurrences() + " locations in "
                                    + relativePath + " — the replacement would be ambiguous. "
                                    + "Provide a more specific search string that matches exactly once "
                                    + "(use `cat` to identify a unique surrounding context).");
                }
                case NO_CHANGE -> {
                    log.info("patch-file: failed {} (NO_CHANGE)", relativePath);
                    yield new ToolResult(false, 1, "",
                            "patch-file produced no changes in " + relativePath
                                    + ". The replacement is identical to the matched text.");
                }
            };
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "patch-file failed: " + e.getMessage());
        }
    }

    /** First line of {@code text}, cut to a length that is safe to put in a log line. */
    private static String firstLineOf(String text) {
        String line = text.lines().findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    // ---- patch-file matching tiers --------------------------------------

    private enum MatchTier {
        EXACT("exact match"),
        NORMALIZED_LINE_ENDINGS("normalized line endings"),
        WHITESPACE_TOLERANT("whitespace-tolerant line match");

        private final String label;
        MatchTier(String label) { this.label = label; }
        String label() { return label; }
    }

    private enum PatchStatus { OK, NOT_FOUND, AMBIGUOUS, NO_CHANGE }

    private record PatchAttempt(PatchStatus status, String newContent, MatchTier matchTier, int occurrences) {
        static PatchAttempt ok(String newContent, MatchTier tier) {
            return new PatchAttempt(PatchStatus.OK, newContent, tier, 1);
        }
        static PatchAttempt notFound() {
            return new PatchAttempt(PatchStatus.NOT_FOUND, null, null, 0);
        }
        static PatchAttempt ambiguous(int occurrences) {
            return new PatchAttempt(PatchStatus.AMBIGUOUS, null, null, occurrences);
        }
        static PatchAttempt noChange() {
            return new PatchAttempt(PatchStatus.NO_CHANGE, null, null, 1);
        }
    }

    /**
     * Tries to apply a patch with progressively more tolerant matching, so the LLM does not
     * waste a round-trip when its search text differs from the file only in line endings
     * (CRLF vs LF) or trailing whitespace.
     *
     * <ol>
     *   <li><b>Tier 1 — exact:</b> {@code String.contains} / {@code replace}.</li>
     *   <li><b>Tier 2 — normalized line endings:</b> CRLF/CR are folded to LF in both file
     *       and search text before matching. The replacement is spliced in and the file's
     *       original dominant line-ending style is restored before writing.</li>
     *   <li><b>Tier 3 — whitespace-tolerant line match:</b> compare line-by-line ignoring
     *       trailing whitespace (and collapsing runs of horizontal whitespace within a
     *       line). On a unique match the original line range is replaced verbatim with the
     *       replacement text (line endings normalized to the file's dominant style).</li>
     * </ol>
     *
     * Tiers stop as soon as a unique match is found.
     */
    private PatchAttempt tryPatch(String original, String search, String replacement) {
        // ---- Tier 1: exact ----
        if (original.contains(search)) {
            int occurrences = countOccurrences(original, search);
            if (occurrences > 1) return PatchAttempt.ambiguous(occurrences);
            String patched = original.replace(search, replacement);
            return patched.equals(original) ? PatchAttempt.noChange()
                    : PatchAttempt.ok(patched, MatchTier.EXACT);
        }

        // ---- Tier 2: normalized line endings ----
        String dominantEol = detectDominantLineEnding(original);
        String origLf   = normalizeToLf(original);
        String searchLf = normalizeToLf(search);
        String replLf   = normalizeToLf(replacement);
        if (!searchLf.equals(search) || !origLf.equals(original)) {
            if (origLf.contains(searchLf)) {
                int occurrences = countOccurrences(origLf, searchLf);
                if (occurrences > 1) return PatchAttempt.ambiguous(occurrences);
                String patchedLf = origLf.replace(searchLf, replLf);
                if (patchedLf.equals(origLf)) return PatchAttempt.noChange();
                return PatchAttempt.ok(applyLineEnding(patchedLf, dominantEol),
                        MatchTier.NORMALIZED_LINE_ENDINGS);
            }
        }

        // ---- Tier 3: whitespace-tolerant line match ----
        String[] fileLines   = origLf.split("\n", -1);
        String[] searchLines = searchLf.split("\n", -1);
        // Drop trailing empty lines from the search (LLM often pads its search text with
        // a final newline that the file does not have at that position).
        int searchLen = trimTrailingEmpty(searchLines);
        if (searchLen == 0) return PatchAttempt.notFound();

        List<Integer> matches = findWhitespaceTolerantMatches(fileLines, searchLines, searchLen);
        if (matches.isEmpty())   return PatchAttempt.notFound();
        if (matches.size() > 1)  return PatchAttempt.ambiguous(matches.size());

        int startLine = matches.getFirst();
        String[] replLines = replLf.split("\n", -1);
        int replLen = trimTrailingEmpty(replLines);

        StringBuilder sb = new StringBuilder(origLf.length() + replLf.length());
        for (int i = 0; i < startLine; i++) {
            if (i > 0) sb.append('\n');
            sb.append(fileLines[i]);
        }
        if (startLine > 0) sb.append('\n');
        for (int i = 0; i < replLen; i++) {
            if (i > 0) sb.append('\n');
            sb.append(replLines[i]);
        }
        for (int i = startLine + searchLen; i < fileLines.length; i++) {
            sb.append('\n').append(fileLines[i]);
        }
        String patchedLf = sb.toString();
        if (patchedLf.equals(origLf)) return PatchAttempt.noChange();
        return PatchAttempt.ok(applyLineEnding(patchedLf, dominantEol),
                MatchTier.WHITESPACE_TOLERANT);
    }

    private static String normalizeToLf(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Returns {@code "\r\n"} if CRLF dominates in the original content, otherwise {@code "\n"}. */
    private static String detectDominantLineEnding(String content) {
        int crlf = 0;
        int lf   = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                if (i > 0 && content.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    private static String applyLineEnding(String lfContent, String eol) {
        return "\n".equals(eol) ? lfContent : lfContent.replace("\n", eol);
    }

    /** Collapses runs of horizontal whitespace and trims trailing whitespace for comparison only. */
    private static String normalizeWhitespaceForCompare(String line) {
        return line.replaceAll("[ \t]+", " ").stripTrailing();
    }

    /** Returns the effective length of {@code lines} after dropping trailing empty entries. */
    private static int trimTrailingEmpty(String[] lines) {
        int len = lines.length;
        while (len > 0 && lines[len - 1].isEmpty()) len--;
        return len;
    }

    /**
     * Returns every starting index in {@code fileLines} where {@code searchLen} consecutive
     * lines match {@code searchLines[0..searchLen)} under
     * {@link #normalizeWhitespaceForCompare(String) whitespace-tolerant} comparison.
     */
    private static List<Integer> findWhitespaceTolerantMatches(String[] fileLines,
                                                                String[] searchLines,
                                                                int searchLen) {
        List<Integer> matches = new ArrayList<>();
        String[] normSearch = new String[searchLen];
        for (int i = 0; i < searchLen; i++) {
            normSearch[i] = normalizeWhitespaceForCompare(searchLines[i]);
        }
        int upper = fileLines.length - searchLen;
        outer:
        for (int i = 0; i <= upper; i++) {
            for (int j = 0; j < searchLen; j++) {
                if (!normalizeWhitespaceForCompare(fileLines[i + j]).equals(normSearch[j])) {
                    continue outer;
                }
            }
            matches.add(i);
        }
        return matches;
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
            if (Files.isDirectory(dirPath)) {
                return new ToolResult(false, 1, "",
                        "mkdir would not change workspace; directory already exists: " + relativePath);
            }
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
                log.warn("delete-file: file did not exist: {}", relativePath);
                return new ToolResult(false, 1, "",
                        "delete-file would not change workspace; file did not exist: " + relativePath);
            }
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "delete-file failed: " + e.getMessage());
        }
    }

    private ToolResult executeSearchTool(Path workspaceDir, String tool, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            log.info("{}: no pattern argument (path .)", tool);
            return new ToolResult(false, -1, "", "Search tool requires at least a pattern argument");
        }

        SearchRequest searchRequest;
        try {
            searchRequest = parseSearchRequest(arguments);
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        String patternText = searchRequest.patternText();
        if (patternText == null && !searchRequest.listFiles()) {
            log.info("{}: no pattern argument (paths {})", tool, searchRequest.paths());
            return new ToolResult(false, -1, "", "Search tool requires at least a pattern argument");
        }
        // As in ripgrep, every given path is searched; one that does not exist is named in a note
        // instead of failing the call, and only if none exists does the call fail.
        List<Path> basePaths = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String relativePath : searchRequest.paths()) {
            try {
                Path basePath = resolveWorkspacePath(workspaceDir, relativePath);
                if (Files.exists(basePath)) {
                    basePaths.add(basePath);
                } else {
                    log.info("{}: path not found: {}", tool, relativePath);
                    notFound.add(relativePath);
                }
            } catch (IOException e) {
                log.info("{}: rejected path {}: {}", tool, relativePath, e.getMessage());
                if (searchRequest.paths().size() == 1) {
                    return new ToolResult(false, -1, "", e.getMessage());
                }
                notFound.add(relativePath + " (" + e.getMessage() + ")");
            }
        }
        if (basePaths.isEmpty()) {
            return new ToolResult(false, 1, "", "Path not found: " + String.join(", ", notFound));
        }
        // Notes go first: outputResult keeps only the head of a long output and would cut them off.
        String notFoundNote = notFound.isEmpty() ? "" : "Not searched, path not found: " + String.join(", ", notFound) + "\n";
        if (searchRequest.listFiles()) {
            return listSearchFiles(workspaceDir, tool, basePaths, searchRequest, notFoundNote);
        }

        Pattern compiledPattern;
        String literalFallbackNote = "";
        try {
            compiledPattern = compileSearchPattern(patternText, searchRequest);
        } catch (PatternSyntaxException e) {
            compiledPattern = Pattern.compile(Pattern.quote(patternText), searchRequest.regexFlags());
            literalFallbackNote = "Note: '" + patternText + "' is not a valid regex (" + e.getDescription()
                    + "), so it was searched as a literal string. Escape special characters (e.g. \\() or use -F.\n";
        }

        SearchMatches found;
        try {
            found = findMatches(workspaceDir, basePaths, compiledPattern, searchRequest);
            if (found.lines().isEmpty() && shouldRetryWithUnescapedAlternation(tool, patternText, searchRequest)) {
                String normalizedPatternText = patternText.replace("\\|", "|");
                Pattern normalizedPattern = compileSearchPattern(normalizedPatternText, searchRequest);
                found = findMatches(workspaceDir, basePaths, normalizedPattern, searchRequest);
                if (!found.lines().isEmpty()) {
                    patternText = normalizedPatternText;
                }
            }
        } catch (IOException e) {
            log.info("{}: search failed in {}: {}", tool, searchRequest.paths(), e.getMessage());
            return new ToolResult(false, -1, "", "Search failed: " + e.getMessage());
        }

        List<String> matches = found.lines();
        String output = matches.isEmpty()
                ? "No matches found for pattern: " + patternText
                : String.join("\n", matches);
        String notes = literalFallbackNote + notFoundNote
                + (found.capped() ? SEARCH_CAPPED_NOTE + "\n" : "");
        return outputResult(true, 0, notes + output, false);
    }

    /**
     * Said when the search stops at {@value #MAX_SEARCH_MATCHES} results, so the model does not take the
     * list as every match in the searched paths.
     */
    static final String SEARCH_CAPPED_NOTE = "Search stopped after " + MAX_SEARCH_MATCHES
            + " results; later lines and files were not searched and may match too. "
            + "Narrow it with a path, -g <glob> or a more specific pattern.";

    /** The result lines of a search, and whether it stopped at {@value #MAX_SEARCH_MATCHES} results. */
    private record SearchMatches(List<String> lines, boolean capped) {
    }

    private Pattern compileSearchPattern(String patternText, SearchRequest searchRequest) {
        if (searchRequest.fixedString()) {
            return Pattern.compile(Pattern.quote(patternText), searchRequest.regexFlags());
        }
        return Pattern.compile(patternText, searchRequest.regexFlags());
    }

    private SearchMatches findMatches(Path workspaceDir, List<Path> basePaths, Pattern pattern,
                                      SearchRequest searchRequest) throws IOException {
        List<String> matches = new ArrayList<>();
        // counts match lines and "more matches" notes, not context lines, against MAX_SEARCH_MATCHES
        int reported = 0;
        // A search across many files keeps a few hits per file, so generated files such as test
        // snapshots cannot flood the result; searching one file returns all of its hits.
        int perFileLimit = basePaths.size() == 1 && Files.isRegularFile(basePaths.getFirst())
                ? Integer.MAX_VALUE : MAX_SEARCH_MATCHES_PER_FILE;
        try (Stream<Path> stream = walkFiles(basePaths, searchRequest.maxDepth())) {
            List<Path> files = stream.filter(this::isReasonableTextFile).toList();

            for (Path file : files) {
                String relativeFilePath = workspaceDir.relativize(file).toString();
                if (!matchesAnyGlob(relativeFilePath, searchRequest.includeGlobs(), searchRequest.caseInsensitive())) {
                    continue;
                }

                List<String> lines = readSearchableLines(file, relativeFilePath);
                if (lines == null) {
                    continue;
                }
                List<Integer> hits = new ArrayList<>();
                long more = 0;
                boolean budgetExhausted = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (!pattern.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    // another result exists but the budget is spent: only then was the search cut short
                    if (searchRequest.filesOnly()) {
                        if (reported >= MAX_SEARCH_MATCHES) {
                            return new SearchMatches(matches, true);
                        }
                        matches.add(relativeFilePath);
                        reported++;
                        break;
                    }
                    if (hits.size() >= perFileLimit) {
                        more++;
                        continue;
                    }
                    if (reported >= MAX_SEARCH_MATCHES) {
                        budgetExhausted = true;
                        break;
                    }
                    hits.add(i);
                    reported++;
                }
                appendSearchHits(matches, relativeFilePath, lines, hits,
                        searchRequest.linesBefore(), searchRequest.linesAfter(),
                        searchRequest.onlyMatching() ? pattern : null);
                if (more > 0) {
                    matches.add(relativeFilePath + ": … " + more
                            + (more == 1 ? " more match" : " more matches") + " in this file; "
                            + "search that file alone to see all.");
                    reported++;
                }
                if (budgetExhausted) {
                    return new SearchMatches(matches, true);
                }
            }
        }
        return new SearchMatches(matches, false);
    }

    /**
     * Adds the hits of one file. Without context a hit is {@code path:N: line}. With {@code -A/-B/-C} the
     * output follows ripgrep: match lines {@code path:N: line}, context lines {@code path-N- line}, and
     * {@code --} between non-adjacent groups, also between files. With {@code onlyMatching} ({@code -o}),
     * as in ripgrep, a hit line becomes one {@code path:N: match} line per non-empty match; context lines
     * stay whole.
     */
    private static void appendSearchHits(List<String> out, String path, List<String> lines, List<Integer> hits,
                                         int before, int after, Pattern onlyMatching) {
        if (before == 0 && after == 0) {
            for (int hit : hits) {
                appendHitLine(out, path, hit, lines.get(hit), onlyMatching);
            }
            return;
        }
        Set<Integer> hitLines = new HashSet<>(hits);
        int printedUpTo = -1;
        for (int hit : hits) {
            int from = Math.max(hit - before, printedUpTo + 1);
            int to = Math.min(hit + after, lines.size() - 1);
            if (from > to) {
                continue; // already printed as context of the previous hit
            }
            if (!out.isEmpty() && (printedUpTo < 0 || from > printedUpTo + 1)) {
                out.add("--");
            }
            for (int k = from; k <= to; k++) {
                if (hitLines.contains(k)) {
                    appendHitLine(out, path, k, lines.get(k), onlyMatching);
                } else {
                    out.add(path + "-" + (k + 1) + "- " + lines.get(k));
                }
            }
            printedUpTo = to;
        }
    }

    /** {@code path:N: line}, or with {@code onlyMatching} one {@code path:N: match} per non-empty match. */
    private static void appendHitLine(List<String> out, String path, int index, String line, Pattern onlyMatching) {
        String prefix = path + ":" + (index + 1) + ": ";
        if (onlyMatching == null) {
            out.add(prefix + line);
            return;
        }
        Matcher matcher = onlyMatching.matcher(line);
        while (matcher.find()) {
            if (!matcher.group().isEmpty()) {
                out.add(prefix + matcher.group());
            }
        }
    }

    /**
     * The lines of one file to search, or {@code null} when the file is skipped. One undecodable file
     * must not abort the walk: a tree carrying binary assets (a .NET {@code wwwroot} with images and
     * fonts) used to fail the whole search on {@code MalformedInputException}.
     */
    private List<String> readSearchableLines(Path file, String relativePath) {
        try {
            byte[] content = Files.readAllBytes(file);
            if (isBinaryContent(content)) {
                log.debug("Search skipped binary file: {}", relativePath);
                return null;
            }
            // Invalid UTF-8 decodes to U+FFFD instead of throwing, so a non-UTF-8 text file stays
            // searchable for its decodable parts — the same lossy decoding ripgrep applies.
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            return decoded.lines().toList();
        } catch (IOException e) {
            log.info("Search skipped unreadable file: {} ({})", relativePath, e.getMessage());
            return null;
        }
    }

    /** A NUL byte in the head marks a binary file — the heuristic ripgrep uses. */
    private static boolean isBinaryContent(byte[] content) {
        int limit = Math.min(content.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code rg --files [path]}: lists the files under the path, filtered by {@code -g} globs. Binary and
     * oversized files are listed too; only the search skips them.
     */
    private ToolResult listSearchFiles(Path workspaceDir, String tool, List<Path> basePaths,
                                       SearchRequest searchRequest, String notFoundNote) {
        List<String> files;
        try (Stream<Path> stream = walkFiles(basePaths, searchRequest.maxDepth())) {
            files = stream
                    .map(path -> workspaceDir.relativize(path).toString())
                    .filter(path -> matchesAnyGlob(path, searchRequest.includeGlobs(), searchRequest.caseInsensitive()))
                    .sorted()
                    .limit(MAX_SEARCH_MATCHES + 1L)
                    .toList();
        } catch (IOException e) {
            log.info("{}: listing files failed in {}: {}", tool,
                    basePaths.stream().map(workspaceDir::relativize).toList(), e.getMessage());
            return new ToolResult(false, -1, "", "Listing files failed: " + e.getMessage());
        }
        if (files.isEmpty()) {
            return outputResult(true, 0, notFoundNote + "No files found.", false);
        }
        String output = String.join("\n", files.subList(0, Math.min(files.size(), MAX_SEARCH_MATCHES)));
        if (files.size() > MAX_SEARCH_MATCHES) {
            output += "\n... (more than " + MAX_SEARCH_MATCHES + " files; narrow it with a path or -g <glob>)";
        }
        return outputResult(true, 0, notFoundNote + output, false);
    }

    /**
     * The visible regular files below each of {@code basePaths}, sorted, each file once even when the
     * paths overlap ({@code ["src", "src/A.java"]}).
     */
    private Stream<Path> walkFiles(List<Path> basePaths, int maxDepth) throws IOException {
        Stream<Path> all = Stream.empty();
        try {
            for (Path basePath : basePaths) {
                all = Stream.concat(all, Files.walk(basePath, maxDepth));
            }
        } catch (IOException | RuntimeException e) {
            all.close(); // the walks opened so far
            throw e;
        }
        return all.filter(this::isVisibleWorkspacePath)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .distinct()
                .sorted();
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

    /** Upper bound for {@code -A}/{@code -B}/{@code -C}; larger values are clamped. */
    static final int MAX_SEARCH_CONTEXT_LINES = 10;
    /** Short flags that change the result and are honoured. */
    private static final Set<Character> SEARCH_RESULT_FLAGS = Set.of('i', 'F', 'l', 'o');
    /**
     * Short flags accepted without effect: line numbers and file names are always printed, the search is
     * always recursive and the pattern always a Java regex.
     */
    private static final Set<Character> SEARCH_NOOP_FLAGS = Set.of('n', 'H', 'r', 'R', 'E');
    /** Short flags that take a value: glob, pattern, context lines, max depth. */
    private static final Set<Character> SEARCH_VALUE_FLAGS = Set.of('g', 'e', 'A', 'B', 'C', 'd');
    /** Long options that take a value ({@code --name value} or {@code --name=value}). */
    private static final Set<String> SEARCH_VALUE_OPTIONS = Set.of("--glob", "--include", "--regexp", "--context",
            "--before-context", "--after-context", "--max-depth", "--maxdepth", "--color", "--colour");
    private static final Set<String> SEARCH_NOOP_OPTIONS = Set.of("--line-number", "--with-filename",
            "--no-heading", "--heading", "--recursive", "--extended-regexp");
    /** A flag is {@code -x...} with letters/digits only, so patterns such as {@code ->} stay positional. */
    private static final Pattern SEARCH_SHORT_FLAGS = Pattern.compile("-[A-Za-z0-9]+");
    /** A long option name; anything else after {@code --} ({@code --depth|shallow}) is a pattern. */
    private static final Pattern SEARCH_LONG_OPTION = Pattern.compile("--[A-Za-z][A-Za-z0-9-]*");
    /** One array element holding a flag and its value, e.g. {@code -A2 "name: Foo"}. */
    private static final Pattern SEARCH_FLAG_WITH_VALUE = Pattern.compile("-{1,2}[A-Za-z][A-Za-z0-9-]*=?\\d*\\s+\\S.*");
    static final String SEARCH_SUPPORTED_FLAGS = "Supported flags: -i, -F, -l, -o, -n, -g <glob>, -e <pattern>, "
            + "-A/-B/-C <lines> (max " + MAX_SEARCH_CONTEXT_LINES + "), -d/--max-depth <n>, --files; "
            + "use -e <pattern> or -- for a pattern that starts with -.";

    /** A glob or pattern value must not be blank: an empty pattern matches every line, an empty glob no file. */
    private static String requireSearchValue(String flag, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Flag " + flag + " needs a non-empty value. " + SEARCH_SUPPORTED_FLAGS);
        }
        return value;
    }

    /**
     * Ripgrep-style arguments: flags may come before, between or after the positionals, as models
     * write {@code rg -i pattern path}. Without {@code -e} the first positional is the pattern and the
     * rest are paths; with {@code -e}/{@code --regexp} (repeatable, the patterns are OR-ed as in ripgrep)
     * all positionals are paths. {@code --} ends the flags, so a pattern may start with {@code -}.
     *
     * <p>A flag that is not supported fails the call instead of being ignored: silently dropping
     * {@code -v} or {@code -w} returns a plausible but wrong result.</p>
     *
     * @throws IllegalArgumentException naming the offending argument and the supported flags
     */
    private SearchRequest parseSearchRequest(List<String> arguments) {
        List<String> positionals = new ArrayList<>();
        List<String> explicitPatterns = new ArrayList<>();
        Set<Character> flags = new LinkedHashSet<>();
        List<String> includeGlobs = new ArrayList<>();
        boolean flagsEnded = false;
        boolean listFiles = false;
        Integer context = null;
        Integer before = null;
        Integer after = null;
        int maxDepth = MAX_SEARCH_DEPTH;

        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument == null || argument.isBlank()) {
                continue;
            }
            if (!flagsEnded) {
                if ("--".equals(argument)) {
                    flagsEnded = true;
                    continue;
                }
                if (SEARCH_FLAG_WITH_VALUE.matcher(argument).matches()) {
                    throw new IllegalArgumentException("Argument '" + argument + "' holds a flag and its value in "
                            + "one element; pass each as its own array element, e.g. [\"-A\", \"2\", \"pattern\"]. "
                            + "If it is a pattern that starts with -, pass it as [\"-e\", \"" + argument
                            + "\"] or put \"--\" before it.");
                }
                // "--depth|shallow" cannot be an option name: as in ripgrep it is the pattern, not an error
                if (argument.startsWith("--") && SEARCH_LONG_OPTION.matcher(argument.split("=", 2)[0]).matches()) {
                    int eq = argument.indexOf('=');
                    String name = eq > 0 ? argument.substring(0, eq) : argument;
                    String value = null;
                    if (SEARCH_VALUE_OPTIONS.contains(name)) {
                        // "--context=3" carries its value inline, "--context 3" takes the next element
                        value = eq > 0 ? argument.substring(eq + 1) : i + 1 < arguments.size() ? arguments.get(++i) : null;
                        if (value == null) {
                            throw new IllegalArgumentException("Option " + name + " needs a value. "
                                    + SEARCH_SUPPORTED_FLAGS);
                        }
                    }
                    switch (name) {
                        case "--files" -> listFiles = true;
                        case "--ignore-case" -> flags.add('i');
                        case "--fixed-strings" -> flags.add('F');
                        case "--files-with-matches" -> flags.add('l');
                        case "--only-matching" -> flags.add('o');
                        case "--glob", "--include" -> includeGlobs.add(requireSearchValue(name, value));
                        case "--regexp" -> explicitPatterns.add(requireSearchValue(name, value));
                        case "--context" -> context = contextLines(name, value);
                        case "--before-context" -> before = contextLines(name, value);
                        case "--after-context" -> after = contextLines(name, value);
                        case "--max-depth", "--maxdepth" -> maxDepth = searchDepth(name, value);
                        default -> {
                            // colour takes a value but has no effect here
                            if (!SEARCH_VALUE_OPTIONS.contains(name) && !SEARCH_NOOP_OPTIONS.contains(name)) {
                                // models also pass text such as "--depth" as the pattern: say how to search for it
                                throw new IllegalArgumentException("Unsupported option " + name + ". To search for "
                                        + "it, pass [\"-e\", \"" + argument + "\"]. " + SEARCH_SUPPORTED_FLAGS);
                            }
                        }
                    }
                    continue;
                }
                if (SEARCH_SHORT_FLAGS.matcher(argument).matches()) {
                    for (int j = 1; j < argument.length(); j++) {
                        char flag = argument.charAt(j);
                        if (SEARCH_VALUE_FLAGS.contains(flag)) {
                            // "-ig *.java" / "-C 3" take the next argument; "-C3" carries its value inline
                            String value = j < argument.length() - 1 ? argument.substring(j + 1)
                                    : i + 1 < arguments.size() ? arguments.get(++i) : null;
                            if (value == null) {
                                throw new IllegalArgumentException("Flag -" + flag + " needs a value. "
                                        + SEARCH_SUPPORTED_FLAGS);
                            }
                            switch (flag) {
                                case 'g' -> includeGlobs.add(requireSearchValue("-g", value));
                                case 'e' -> explicitPatterns.add(requireSearchValue("-e", value));
                                case 'A' -> after = contextLines("-A", value);
                                case 'B' -> before = contextLines("-B", value);
                                case 'C' -> context = contextLines("-C", value);
                                default -> maxDepth = searchDepth("-d", value);
                            }
                            break;
                        }
                        if (SEARCH_RESULT_FLAGS.contains(flag)) {
                            flags.add(flag);
                        } else if (!SEARCH_NOOP_FLAGS.contains(flag)) {
                            throw new IllegalArgumentException("Unsupported flag -" + flag
                                    + (argument.length() > 2 ? " in " + argument : "") + ". " + SEARCH_SUPPORTED_FLAGS);
                        }
                    }
                    continue;
                }
                // "-g=*.java" or "-g*.java" is neither a flag form above nor meant as a pattern; taken as
                // the pattern it would only report "No matches found", a plausible but wrong answer
                if (argument.length() > 2 && argument.charAt(0) == '-' && Character.isLetter(argument.charAt(1))) {
                    throw new IllegalArgumentException("Cannot read '" + argument + "' as flags; pass a flag and "
                            + "its value as separate elements, e.g. [\"-g\", \"*.java\"]. If it is a pattern that "
                            + "starts with -, pass it as [\"-e\", \"" + argument + "\"] or put \"--\" before it. "
                            + SEARCH_SUPPORTED_FLAGS);
                }
            }
            positionals.add(argument);
        }

        boolean fixedString = flags.contains('F');
        String patternText;
        List<String> paths;
        // as in ripgrep, every positional after the pattern is a path; all of them are searched
        if (!explicitPatterns.isEmpty()) {
            paths = positionals;
            patternText = joinAlternatives(explicitPatterns, fixedString);
            // several -F patterns are quoted into one regex, which must not be quoted again
            fixedString = fixedString && explicitPatterns.size() == 1;
        } else if (listFiles) {
            // "rg --files src" takes no pattern: its positionals are the paths
            patternText = null;
            paths = positionals;
        } else {
            patternText = positionals.isEmpty() ? null : positionals.getFirst();
            paths = positionals.isEmpty() ? List.of() : positionals.subList(1, positionals.size());
        }
        if (paths.isEmpty()) {
            paths = List.of(".");
        }

        int regexFlags = flags.contains('i') ? Pattern.CASE_INSENSITIVE : 0;
        boolean filesOnly = flags.contains('l');
        boolean caseInsensitive = flags.contains('i');
        // as in ripgrep, -A and -B win over -C whatever the order
        int linesBefore = before != null ? before : context != null ? context : 0;
        int linesAfter = after != null ? after : context != null ? context : 0;
        return new SearchRequest(patternText, List.copyOf(paths), regexFlags, fixedString, filesOnly,
                caseInsensitive, includeGlobs, listFiles, linesBefore, linesAfter, maxDepth, flags.contains('o'));
    }

    /**
     * A {@code -d}/{@code --max-depth} value, counted like ripgrep: 1 searches only the entries directly
     * below the path, 0 only the path itself. Values above {@value #MAX_SEARCH_DEPTH} are clamped.
     */
    private static int searchDepth(String flag, String value) {
        if (!isDigits(value)) {
            throw new IllegalArgumentException(flag + " needs a non-negative number, got '" + value + "'. "
                    + SEARCH_SUPPORTED_FLAGS);
        }
        return value.length() > 4 ? MAX_SEARCH_DEPTH : Math.min(Integer.parseInt(value), MAX_SEARCH_DEPTH);
    }

    /** A context line count; values above {@value #MAX_SEARCH_CONTEXT_LINES} are clamped. */
    private static int contextLines(String flag, String value) {
        if (!isDigits(value)) {
            throw new IllegalArgumentException(flag + " needs a number of lines, got '" + value + "'. "
                    + SEARCH_SUPPORTED_FLAGS);
        }
        return value.length() > 4 ? MAX_SEARCH_CONTEXT_LINES
                : Math.min(Integer.parseInt(value), MAX_SEARCH_CONTEXT_LINES);
    }

    /** Ripgrep ORs repeated {@code -e} patterns; with {@code -F} each one is matched literally. */
    private static String joinAlternatives(List<String> patterns, boolean fixedString) {
        if (patterns.size() == 1) {
            return patterns.getFirst();
        }
        return patterns.stream()
                .map(p -> fixedString ? Pattern.quote(p) : "(?:" + p + ")")
                .collect(Collectors.joining("|"));
    }

    private record SearchRequest(String patternText, List<String> paths, int regexFlags,
                                 boolean fixedString, boolean filesOnly,
                                 boolean caseInsensitive, List<String> includeGlobs, boolean listFiles,
                                 int linesBefore, int linesAfter, int maxDepth, boolean onlyMatching) {
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
                    .filter(this::isVisibleWorkspacePath)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .map(workspaceDir::relativize)
                    .map(Path::toString)
                    .filter(path -> matchesGlob(path, globPattern, request.caseInsensitive()))
                    .filter(path -> matchesAnyGlob(path, request.includeGlobs(), request.caseInsensitive()))
                    .toList();

            String output = matches.isEmpty()
                    ? "No files found for pattern: " + globPattern
                    : String.join("\n", matches);
            return outputResult(true, 0, output, false);
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
            // A blank slot is a property the caller did not send: ToolArguments keeps the declared
            // positions, so cat's {"path": "…", "endLine": 50} must not arrive as startLine=50.
            if (arguments.size() > 1 && arguments.get(1) != null && !arguments.get(1).isBlank()) {
                startLine = Integer.parseInt(arguments.get(1));
            }
            if (arguments.size() > 2 && arguments.get(2) != null && !arguments.get(2).isBlank()) {
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
            return outputResult(true, 0, output.toString().stripTrailing(), false);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", "cat failed: " + e.getMessage());
        }
    }

    private static final Pattern GIT_LOG_COUNT_FLAG = Pattern.compile("-(\\d+)|(?:-n|--max-count=)(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    static final int MAX_GIT_LOG_LIMIT = 200;
    /** Filters passed to git, always as {@code --name=value} so a value can never become an option itself. */
    private static final Map<String, String> GIT_LOG_VALUE_FILTERS = Map.of(
            "--author", "--author", "--committer", "--committer", "--grep", "--grep",
            "--since", "--since", "--after", "--since", "--until", "--until", "--before", "--until");
    private static final Set<String> GIT_LOG_SWITCHES = Set.of(
            "-i", "--regexp-ignore-case", "--all-match", "--no-merges", "--merges");
    /** Unsupported options that take a value: the value is ignored with them instead of becoming the path. */
    private static final Set<String> GIT_LOG_IGNORED_VALUE_OPTIONS = Set.of(
            "--format", "--pretty", "--date", "--output", "--skip", "-L", "--decorate");

    /**
     * Arguments are {@code [path] [limit]} in any order, plus an allow-list of filters: {@code --author},
     * {@code --committer}, {@code --since}/{@code --after}, {@code --until}/{@code --before},
     * {@code --grep} (with {@code -i}, {@code --all-match}), {@code -S}/{@code -G} pickaxe, {@code --no-merges},
     * {@code --merges} and {@code --follow} (with a path). A count ({@code -20}, {@code -n 20},
     * {@code --max-count=20}) sets the limit, clamped to 1..{@value #MAX_GIT_LOG_LIMIT}. Every other option is
     * ignored and named in the result, so the model knows its filter had no effect.
     */
    private ToolResult executeGitLogTool(Path workspaceDir, List<String> arguments) {
        String relativePath = null;
        int limit = DEFAULT_GIT_LOG_LIMIT;
        List<String> filters = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        boolean follow = false;
        List<String> args = arguments == null ? List.of() : arguments;
        for (int i = 0; i < args.size(); i++) {
            String argument = args.get(i);
            if (argument == null || argument.isBlank()) {
                continue;
            }
            String name = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
            Matcher count = GIT_LOG_COUNT_FLAG.matcher(argument);
            if (count.matches()) {
                limit = clampGitLogLimit(count.group(1) != null ? count.group(1) : count.group(2));
            } else if (("-n".equals(argument) || "--max-count".equals(argument))
                    && i + 1 < args.size() && isDigits(args.get(i + 1))) {
                limit = clampGitLogLimit(args.get(++i));
            } else if (GIT_LOG_VALUE_FILTERS.containsKey(name)) {
                String value = argument.contains("=") ? argument.substring(argument.indexOf('=') + 1)
                        : i + 1 < args.size() ? args.get(++i) : null;
                if (value != null && !value.isBlank()) {
                    filters.add(GIT_LOG_VALUE_FILTERS.get(name) + "=" + value);
                }
            } else if (argument.startsWith("-S") || argument.startsWith("-G")) {
                // pickaxe: "-S text" or "-Stext"; git takes the value glued to the flag
                String value = argument.length() > 2 ? argument.substring(2)
                        : i + 1 < args.size() ? args.get(++i) : null;
                if (value != null && !value.isEmpty()) {
                    filters.add(argument.substring(0, 2) + value);
                }
            } else if (GIT_LOG_SWITCHES.contains(argument)) {
                filters.add(argument);
            } else if ("--follow".equals(argument)) {
                follow = true;
            } else if (argument.startsWith("-")) {
                String skippedValue = GIT_LOG_IGNORED_VALUE_OPTIONS.contains(argument) && i + 1 < args.size()
                        ? " " + args.get(++i) : "";
                ignored.add(argument + skippedValue);
            } else if (isDigits(argument)) {
                limit = clampGitLogLimit(argument);
            } else if (relativePath == null) {
                relativePath = argument;
            } else {
                ignored.add(argument);
            }
        }

        List<String> command = new ArrayList<>(List.of(
                "git", "log", "--date=short", "--pretty=format:%h %ad %an %s", "-n", String.valueOf(limit)));
        command.addAll(filters);
        if (relativePath != null) {
            try {
                resolveWorkspacePath(workspaceDir, relativePath);
            } catch (IOException e) {
                return new ToolResult(false, -1, "", e.getMessage());
            }
            if (follow) {
                command.add("--follow");
            }
            command.add("--");
            command.add(relativePath);
        } else if (follow) {
            ignored.add("--follow (needs a path)");
        }
        ToolResult result = executeCommand(workspaceDir, command.toArray(String[]::new));
        if (!result.success()) {
            return result;
        }
        String output = result.output();
        if (output == null || output.isBlank()) {
            output = (relativePath == null ? "No commits found" : "No commits found for path: " + relativePath)
                    + (filters.isEmpty() ? "" : " (filters: " + String.join(" ", filters) + ")")
                    + (relativePath == null && filters.isEmpty() ? "." : "");
        }
        if (!ignored.isEmpty()) {
            output = output.stripTrailing() + "\nIgnored unsupported options: " + String.join(", ", ignored);
        }
        return outputResult(true, 0, output, result.outputTruncated());
    }

    /** A count from the model; anything outside 1..{@value #MAX_GIT_LOG_LIMIT}, also absurdly long digit runs, is clamped. */
    private static int clampGitLogLimit(String digits) {
        if (digits.length() > 4) {
            return MAX_GIT_LOG_LIMIT;
        }
        return Math.clamp(Integer.parseInt(digits), 1, MAX_GIT_LOG_LIMIT);
    }

    private static boolean isDigits(String value) {
        return value != null && DIGITS.matcher(value).matches();
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
                    .filter(this::isVisibleWorkspacePath)
                    .sorted(Comparator.naturalOrder())
                    .map(path -> formatTreeEntry(basePath, path))
                    .toList();
            return outputResult(true, 0, String.join("\n", lines), false);
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

    /**
     * The {@code -g} filters, as in ripgrep: a glob starting with {@code !} excludes the paths it
     * matches; if any plain glob is given, the path must also match one of them.
     */
    private boolean matchesAnyGlob(String path, List<String> globPatterns, boolean caseInsensitive) {
        if (globPatterns == null || globPatterns.isEmpty()) {
            return true;
        }
        boolean anyInclude = false;
        boolean included = false;
        for (String glob : globPatterns) {
            if (glob.startsWith("!")) {
                if (matchesGlob(path, glob.substring(1), caseInsensitive)) {
                    return false;
                }
            } else {
                anyInclude = true;
                included = included || matchesGlob(path, glob, caseInsensitive);
            }
        }
        return !anyInclude || included;
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

    /**
     * Glob to regex: {@code *} and {@code **} match any run of characters, {@code ?} one character,
     * {@code **}{@code /} also matches no directory at all ({@code **}{@code /*.java} finds
     * {@code A.java} at the root), and {@code {a,b}} matches either alternative. A stray {@code ,}
     * or {@code }} outside braces is literal.
     */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int braceDepth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 2;
                continue;
            }
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '{' -> {
                    regex.append("(?:");
                    braceDepth++;
                }
                case '}' -> {
                    if (braceDepth > 0) {
                        regex.append(')');
                        braceDepth--;
                    } else {
                        regex.append(Pattern.quote("}"));
                    }
                }
                case ',' -> regex.append(braceDepth > 0 ? "|" : Pattern.quote(","));
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        // an unclosed "{" would leave an invalid group: close it, matching what was written
        regex.append(")".repeat(braceDepth));
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

    // ---- ctags-signatures tool ----

    private static final int DEFAULT_CTAGS_SIGNATURES_LIMIT = 100;
    private static final int MAX_CTAGS_SIGNATURES_LIMIT = 500;

    private ToolResult executeCtagsSignaturesTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "ctags-signatures requires a file path");
        }
        String relativePath = arguments.getFirst();
        int limit = DEFAULT_CTAGS_SIGNATURES_LIMIT;
        if (arguments.size() > 1 && isInteger(arguments.get(1))) {
            limit = Integer.parseInt(arguments.get(1));
        }
        limit = Math.clamp(limit, 1, MAX_CTAGS_SIGNATURES_LIMIT);

        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        if (!Files.isRegularFile(filePath)) {
            return new ToolResult(false, 1, "", "File not found: " + relativePath);
        }

        String[] command = {"ctags", "--output-format=json", "--fields=+neKz",
                filePath.toAbsolutePath().toString()};
        ToolResult raw = executeCommand(workspaceDir, command);
        if (!raw.success()) {
            return raw;
        }

        String formatted = formatCtagsSignatures(relativePath, raw.output(), limit);
        return outputResult(true, 0, formatted, raw.outputTruncated());
    }

    /**
     * Parses ctags JSON lines into a Markdown code block with pseudo-code signatures.
     * Filters to classes, interfaces, methods, functions, constructors, macros, namespaces.
     * Skips variables and local scopes to minimise context consumption.
     * <p>
     * Ctags 5.9 (Ubuntu Noble default) does not emit a distinct {@code constructor} kind —
     * constructors are tagged as {@code method}. We detect them by name equality with the
     * enclosing class. Ctags 6.x adds a native {@code constructor} kind which we also handle.
     */
    static String formatCtagsSignatures(String filePath, String ctagsJsonOutput, int limit) {
        if (ctagsJsonOutput == null || ctagsJsonOutput.isBlank()) {
            return "### `" + new File(filePath).getName() + "`\n```text\nNo classes or methods detected.\n```";
        }

        List<CtagsTag> tags = new ArrayList<>();
        for (String line : ctagsJsonOutput.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            CtagsTag tag = parseCtagsLine(line);
            if (tag != null) {
                tags.add(tag);
            }
        }

        String langMarker = languageMarker(filePath);
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(new File(filePath).getName()).append("\n");
        sb.append("```").append(langMarker).append("\n");

        int count = 0;
        for (CtagsTag tag : tags) {
            if (count >= limit) {
                sb.append("// ... (").append(tags.size() - count)
                        .append(" more signature(s) omitted)\n");
                break;
            }
            String rendered = renderCtagsTag(tag);
            if (rendered != null) {
                sb.append(rendered).append("\n");
                count++;
            }
        }

        if (count == 0) {
            sb.append("// No classes or methods detected in this file.\n");
        }
        sb.append("```");
        return sb.toString();
    }

    private record CtagsTag(String name, String kind, String signature, String scope, String scopeKind) {}

    private static CtagsTag parseCtagsLine(String jsonLine) {
        // ctags JSON lines are flat: {"_type": "tag", "name": "foo", "kind": "class", ...}
        // Use minimal extraction to avoid a full Jackson dependency in a static method.
        String name = extractCtagsField(jsonLine, "name");
        String kind = extractCtagsField(jsonLine, "kind");
        if (name == null || kind == null) return null;
        return new CtagsTag(name, kind,
                extractCtagsField(jsonLine, "signature"),
                extractCtagsField(jsonLine, "scope"),
                extractCtagsField(jsonLine, "scopeKind"));
    }

    private static String extractCtagsField(String jsonLine, String fieldName) {
        // ctags JSON may or may not have a space after the colon (both formats exist
        // across versions). Find the key, skip optional whitespace, then read the value.
        String keyPattern = "\"" + fieldName + "\"";
        int keyStart = jsonLine.indexOf(keyPattern);
        if (keyStart == -1) return null;
        int pos = keyStart + keyPattern.length();
        while (pos < jsonLine.length()
                && (jsonLine.charAt(pos) == ' ' || jsonLine.charAt(pos) == ':')) {
            pos++;
        }
        if (pos >= jsonLine.length() || jsonLine.charAt(pos) != '"') return null;
        int start = pos + 1;
        // Walk forward, handling JSON string escapes
        StringBuilder value = new StringBuilder();
        for (int i = start; i < jsonLine.length(); i++) {
            char c = jsonLine.charAt(i);
            if (c == '\\' && i + 1 < jsonLine.length()) {
                value.append(jsonLine.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    /**
     * Renders a single ctags tag as a pseudo-code declaration line.
     * Returns null for kinds we intentionally skip (variables, local scopes).
     */
    static String renderCtagsTag(CtagsTag tag) {
        String indent = tag.scope() != null ? "  " : "";
        return switch (tag.kind().toLowerCase()) {
            case "c", "class" -> "class " + tag.name();
            case "i", "interface" -> "interface " + tag.name();
            case "namespace", "module" -> "namespace " + tag.name();
            case "f", "function" -> indent + "function " + tag.name()
                    + (tag.signature() != null ? tag.signature() : "()");
            case "constructor" -> indent + "constructor " + tag.name()
                    + (tag.signature() != null ? tag.signature() : "()");
            case "m", "method" -> {
                // ctags 5.9: constructors are tagged as "method" — detect by name == enclosing class
                if (tag.scope() != null && tag.name().equals(tag.scope())) {
                    yield indent + "constructor " + tag.scope()
                            + (tag.signature() != null ? tag.signature() : "()");
                }
                yield indent + "method " + tag.name()
                        + (tag.signature() != null ? tag.signature() : "()");
            }
            case "member" -> {
                // Python (and some other languages) emits "member" for class methods.
                // Render as a method when scoped inside a class; skip top-level members.
                if (tag.scope() != null) {
                    yield indent + "method " + tag.name()
                            + (tag.signature() != null ? tag.signature() : "()");
                }
                yield null;
            }
            case "macro" -> indent + "macro " + tag.name();
            default -> null;  // skip variables, enums, local scopes, etc.
        };
    }

    private static String languageMarker(String filePath) {
        String name = filePath.toLowerCase();
        if (name.endsWith(".py") || name.endsWith(".pyi")) return "python";
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return "typescript";
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs")) return "javascript";
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".cs")) return "csharp";
        if (name.endsWith(".c") || name.endsWith(".h")) return "c";
        if (name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx") || name.endsWith(".hpp")) return "cpp";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".rs")) return "rust";
        if (name.endsWith(".rb")) return "ruby";
        if (name.endsWith(".swift")) return "swift";
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "kotlin";
        if (name.endsWith(".scala")) return "scala";
        return "text";
    }

    // ---- ctags-deps tool ----

    private ToolResult executeCtagsDepsTool(Path workspaceDir, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new ToolResult(false, -1, "", "ctags-deps requires a file path");
        }
        String relativePath = arguments.getFirst();
        Path filePath;
        try {
            filePath = resolveWorkspacePath(workspaceDir, relativePath);
        } catch (IOException e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
        if (!Files.isRegularFile(filePath)) {
            return new ToolResult(false, 1, "", "File not found: " + relativePath);
        }

        // --extras=+r includes reference tags (imports, includes), which are
        // essential for dependency extraction. Without it, ctags omits import/
        // include statements entirely.
        // Java-side post-filtering in formatCtagsDependencies() selects the
        // tags we care about by examining the "extras" and "kind" fields.
        String[] command = {"ctags", "--output-format=json",
                "--extras=+r", "--fields=+k",
                filePath.toAbsolutePath().toString()};
        ToolResult raw = executeCommand(workspaceDir, command);
        if (!raw.success()) {
            log.warn("Error executing ctags-deps tool: {}", raw.output());
            return raw;
        }

        String json = formatCtagsDependencies(new File(relativePath).getName(), raw.output());
        return outputResult(true, 0, json, raw.outputTruncated());
    }

    /**
     * Parses ctags JSON lines into a compact JSON dependency map.
     * Extracts imports/includes and namespace/package declarations.
     */
    static String formatCtagsDependencies(String fileName, String ctagsJsonOutput) {
        List<String> deps = new ArrayList<>();
        String namespace = null;

        if (ctagsJsonOutput != null && !ctagsJsonOutput.isBlank()) {
            for (String line : ctagsJsonOutput.split("\n")) {
                line = line.strip();
                if (line.isEmpty()) continue;
                String name = extractCtagsField(line, "name");
                String kind = extractCtagsField(line, "kind");
                if (name == null || kind == null) continue;
                String kindLower = kind.toLowerCase();
                // ctags tags imports/includes with extras=reference.
                // This is the reliable cross-language signal — the kind
                // letter alone is ambiguous (e.g. Java emits kind=package
                // for both "package com.foo" and "import com.foo.Bar").
                String extras = extractCtagsField(line, "extras");
                boolean isReference = extras != null && extras.contains("reference");
                if (isReference) {
                    deps.add(name);
                } else if ("namespace".equals(kindLower) || "package".equals(kindLower)
                        || "module".equals(kindLower)) {
                    if (namespace == null) {
                        namespace = name;
                    }
                }
            }
        }

        // Compact single-line JSON (not pretty-printed — the AI doesn't care)
        StringBuilder json = new StringBuilder();
        json.append("{\"file\":\"").append(escapeJson(fileName)).append("\"");
        json.append(",\"declared_namespace_or_package\":\"").append(
                namespace != null ? escapeJson(namespace) : "none").append("\"");
        json.append(",\"dependencies\":[");
        for (int i = 0; i < deps.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(deps.get(i))).append("\"");
        }
        json.append("]}");
        return json.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }


    private Path resolveWorkspacePath(Path workspaceDir, String relativePath) throws IOException {
        try {
            return org.remus.giteabot.util.WorkspacePaths.resolveInsideWorkspace(workspaceDir, relativePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** True when any path segment belongs to the repository's internal Git metadata. */
    private boolean isGitInternalPath(Path path) {
        for (Path segment : path) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Symlinks can point outside the workspace even when recursive walks do not follow directories. */
    private boolean isVisibleWorkspacePath(Path path) {
        return !isGitInternalPath(path) && !Files.isSymbolicLink(path);
    }

    private ToolResult executeCommand(Path workspaceDir, String[] command) {
        return executeCommand(workspaceDir, command, Map.of());
    }

    /**
     * As {@link #executeCommand(Path, String[])}, plus variables the tool needs beyond the
     * scrub allow-list (Corepack's non-interactive download switch). They are applied after
     * the scrub, so they cannot reintroduce an application secret.
     */
    private ToolResult executeCommand(Path workspaceDir, String[] command,
                                      Map<String, String> extraEnvironment) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);
            // Untrusted repository code (package-manager scripts, test fixtures) must
            // never see application secrets such as database or AI provider keys.
            ProcessSupport.scrubEnvironment(pb);
            if (!extraEnvironment.isEmpty()) {
                pb.environment().putAll(extraEnvironment);
            }

            int timeoutSeconds = agentConfig.getValidation().getToolTimeoutSeconds();
            // The byte cap is the hard memory bound while draining the pipe; the
            // character cap below is the presentation limit. The +1_000 headroom
            // makes the char cap the effective limit for ASCII-dominant output,
            // while multi-byte-heavy output may hit the byte cap first.
            ProcessSupport.CommandResult result = ProcessSupport.run(pb, timeoutSeconds,
                    TimeUnit.SECONDS, MAX_TOOL_OUTPUT_CHARS + 1_000);

            if (!result.finished()) {
                return new ToolResult(false, -1, "",
                        "Tool execution timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = result.exitCode();
            boolean success = exitCode == 0;

            log.info("Tool {} with exit code {}",
                    success ? "succeeded" : "failed", exitCode);

            return outputResult(success, exitCode, result.output(), result.outputTruncated());

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

    private ToolResult outputResult(boolean success, int exitCode, String output, boolean alreadyTruncated) {
        boolean truncated = alreadyTruncated || output != null && output.length() > MAX_TOOL_OUTPUT_CHARS;
        return new ToolResult(success, exitCode, truncateOutput(output), "", truncated);
    }
}
