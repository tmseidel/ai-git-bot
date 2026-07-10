package org.remus.giteabot.prworkflow.agentreview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff into a compact file-level summary and supports
 * per-file hunk extraction for the {@code pr-diff} tool.
 * <p>
 * This class transforms a full unified diff (which can be 60K+ chars for large PRs)
 * into a lightweight summary showing only file paths and change counts. The agent
 * can then use the {@code pr-diff} tool to load specific file hunks on demand,
 * keeping the initial context small and focused.
 */
public final class DiffSummary {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern PLUS_PLUS_PLUS = Pattern.compile("^\\+\\+\\+ (?:b/)?(.+)$");
    private static final Pattern MINUS_MINUS_MINUS = Pattern.compile("^--- (?:a/)?(.+)$");
    private static final Pattern RENAME_TO = Pattern.compile("^rename to (.+)$");

    /**
     * Represents a single changed file in the diff.
     *
     * @param path      repository-relative file path
     * @param additions number of lines added
     * @param deletions number of lines deleted
     * @param rawBlock  the raw diff block for this file (for per-file extraction)
     */
    public record FileEntry(String path, int additions, int deletions, String rawBlock) {
    }

    private final List<FileEntry> entries;
    private final int totalAdditions;
    private final int totalDeletions;

    private DiffSummary(List<FileEntry> entries, int totalAdditions, int totalDeletions) {
        this.entries = Collections.unmodifiableList(entries);
        this.totalAdditions = totalAdditions;
        this.totalDeletions = totalDeletions;
    }

    /**
     * Parse a unified diff string into a DiffSummary.
     *
     * @param fullDiff the complete unified diff (may be null or empty)
     * @return a DiffSummary with parsed file entries, or an empty summary if parsing fails
     */
    public static DiffSummary parse(String fullDiff) {
        if (fullDiff == null || fullDiff.isBlank()) {
            return new DiffSummary(List.of(), 0, 0);
        }

        List<FileEntry> entries = new ArrayList<>();
        String[] lines = fullDiff.split("\n");
        
        int i = 0;
        while (i < lines.length) {
            // Look for "diff --git" marker
            if (lines[i].startsWith("diff --git ")) {
                int blockStart = i;
                Matcher headerMatcher = DIFF_HEADER.matcher(lines[i]);
                String pathFromHeader = null;
                if (headerMatcher.matches()) {
                    pathFromHeader = headerMatcher.group(2);
                }
                
                // Collect the entire block until the next "diff --git" or end
                StringBuilder blockBuilder = new StringBuilder();
                String path = null;
                int additions = 0;
                int deletions = 0;
                boolean isBinary = false;
                
                i++;
                while (i < lines.length && !lines[i].startsWith("diff --git ")) {
                    String line = lines[i];
                    blockBuilder.append(line).append("\n");
                    
                    // Extract path from +++ line (preferred) or --- line
                    if (path == null) {
                        Matcher plusMatcher = PLUS_PLUS_PLUS.matcher(line);
                        if (plusMatcher.matches()) {
                            String p = plusMatcher.group(1);
                            if (!"/dev/null".equals(p)) {
                                path = p;
                            }
                        } else {
                            Matcher minusMatcher = MINUS_MINUS_MINUS.matcher(line);
                            if (minusMatcher.matches()) {
                                String p = minusMatcher.group(1);
                                if (!"/dev/null".equals(p)) {
                                    path = p;
                                }
                            }
                        }
                    }
                    
                    // Check for rename
                    if (path == null) {
                        Matcher renameMatcher = RENAME_TO.matcher(line);
                        if (renameMatcher.matches()) {
                            path = renameMatcher.group(1);
                        }
                    }
                    
                    // Check for binary file
                    if (line.startsWith("Binary files ")) {
                        isBinary = true;
                    }
                    
                    // Count additions and deletions
                    if (line.startsWith("+") && !line.startsWith("+++")) {
                        additions++;
                    } else if (line.startsWith("-") && !line.startsWith("---")) {
                        deletions++;
                    }
                    
                    i++;
                }
                
                // Fallback to path from header if not found in block
                if (path == null && pathFromHeader != null) {
                    path = pathFromHeader;
                }
                
                if (path != null) {
                    String rawBlock = blockBuilder.toString();
                    if (isBinary) {
                        rawBlock = "Binary file changed\n";
                    }
                    entries.add(new FileEntry(path, additions, deletions, rawBlock));
                }
            } else {
                i++;
            }
        }
        
        int totalAdd = entries.stream().mapToInt(FileEntry::additions).sum();
        int totalDel = entries.stream().mapToInt(FileEntry::deletions).sum();
        
        return new DiffSummary(entries, totalAdd, totalDel);
    }

    /**
     * Returns a compact stat line like "12 files changed, 345 insertions(+), 89 deletions(-)".
     */
    public String statLine() {
        int fileCount = entries.size();
        StringBuilder sb = new StringBuilder();
        sb.append(fileCount).append(" file").append(fileCount != 1 ? "s" : "");
        if (fileCount > 0) {
            sb.append(" changed");
            if (totalAdditions > 0 || totalDeletions > 0) {
                sb.append(", ");
                sb.append(totalAdditions).append(" insertion").append(totalAdditions != 1 ? "s" : "");
                sb.append("(+), ");
                sb.append(totalDeletions).append(" deletion").append(totalDeletions != 1 ? "s" : "");
                sb.append("(-)");
            }
        }
        return sb.toString();
    }

    /**
     * Returns a Markdown table of changed files with +/- counts.
     * If there are more than 100 files, truncates and adds a note.
     */
    public String fileTable() {
        if (entries.isEmpty()) {
            return "(no changes)";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("| File | +/- |\n");
        sb.append("|---|---|\n");
        
        int limit = Math.min(entries.size(), 100);
        for (int i = 0; i < limit; i++) {
            FileEntry entry = entries.get(i);
            sb.append("| ").append(entry.path()).append(" | ");
            sb.append("+").append(entry.additions()).append(" / -").append(entry.deletions());
            sb.append(" |\n");
        }
        
        if (entries.size() > 100) {
            int remaining = entries.size() - 100;
            sb.append("\n... and ").append(remaining).append(" more files. ");
            sb.append("Use `find` or `rg` to locate specific files.\n");
        }
        
        return sb.toString();
    }

    /**
     * Returns all changed file paths.
     */
    public List<String> changedFiles() {
        return entries.stream().map(FileEntry::path).toList();
    }

    /**
     * Extract the diff hunks for a specific file path.
     *
     * @param filePath the repository-relative path to extract
     * @return the diff block for that file, or null if not found
     */
    public String fileDiff(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        
        for (FileEntry entry : entries) {
            if (entry.path().equals(filePath)) {
                return entry.rawBlock();
            }
        }
        
        return null;
    }

    /**
     * Returns the number of changed files.
     */
    public int fileCount() {
        return entries.size();
    }

    /**
     * Returns true if this summary has no entries.
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
