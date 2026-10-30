package org.remus.giteabot.prworkflow.agentreview.memory;

import java.util.List;

final class MemoryChecks {
    private MemoryChecks() { }

    static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    static void text(String value, int limit, boolean nonblank, String field) {
        require(value != null && value.length() <= limit && (!nonblank || !value.isBlank()), "Invalid " + field);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)), "Invalid Unicode in " + field);
            } else {
                require(!Character.isLowSurrogate(c), "Invalid Unicode in " + field);
            }
        }
    }

    static <T> List<T> copy(List<T> values, int limit, String field) {
        require(values != null && values.size() <= limit, "Invalid " + field + " count");
        values.forEach(v -> require(v != null, "Null entry in " + field));
        return List.copyOf(values);
    }

    static void runId(Long id) {
        require(id == null || id > 0, "Invalid lineage run id");
    }

    static void path(String path) {
        text(path, ReviewMemory.MAX_PATH, true, "path");
        require(!path.startsWith("/") && !path.contains("\\") && !path.contains(":")
                && path.chars().noneMatch(Character::isISOControl), "Invalid repository-relative path");
        for (String part : path.split("/", -1)) {
            require(!part.isEmpty() && !part.equals(".") && !part.equals(".."), "Invalid path component");
        }
    }

    static void finding(ReviewMemory.Severity severity, String category, String title, String description,
                        String recommendation, String file, Integer line, String cwe, String owasp) {
        require(severity != null, "Missing severity");
        text(category, 128, true, "category");
        text(title, ReviewMemory.MAX_TITLE, true, "title");
        text(description, ReviewMemory.MAX_DESCRIPTION, true, "description");
        text(recommendation, ReviewMemory.MAX_RECOMMENDATION, false, "recommendation");
        if (file != null) {
            path(file);
        }
        require(line == null || (line > 0 && file != null), "line requires a file and positive number");
        if (cwe != null) {
            text(cwe, 128, true, "cwe");
        }
        if (owasp != null) {
            text(owasp, 128, true, "owasp");
        }
    }
}
