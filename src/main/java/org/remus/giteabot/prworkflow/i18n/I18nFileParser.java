package org.remus.giteabot.prworkflow.i18n;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the two i18n file shapes the {@link I18nCoverageWorkflow} supports into
 * an ordered flat {@code key -> value} map, and extracts the locale token and
 * the "family" a locale file belongs to.
 *
 * <ul>
 *   <li>{@code .properties} — a Java resource bundle. Parsed line-by-line so key
 *       order is preserved; blank lines and {@code #} / {@code !} comment lines
 *       are ignored; the key/value separator is the first unescaped {@code =} or
 *       {@code :}.</li>
 *   <li>{@code .json} — a (possibly nested) JSON object. Nested objects are
 *       flattened to dot-notation keys ({@code a.b.c}); scalar leaves are
 *       stringified.</li>
 * </ul>
 *
 * <h2>Locale &amp; family extraction</h2>
 * <p>The <em>family</em> is the set of locale files that translate the same
 * logical bundle, differing only by their locale token. It is identified by the
 * directory + base name + extension, with the locale token stripped out:</p>
 * <ul>
 *   <li>{@code i18n/messages_en.properties} → locale {@code en}, family
 *       {@code i18n/messages|.properties}</li>
 *   <li>{@code i18n/messages_de_DE.properties} → locale {@code de_DE}, family
 *       {@code i18n/messages|.properties}</li>
 *   <li>{@code i18n/messages.properties} (no suffix) → locale {@code ""} (the
 *       implicit default), family {@code i18n/messages|.properties}</li>
 *   <li>{@code i18n/en.json} → locale {@code en}, family {@code i18n/|.json}</li>
 *   <li>{@code i18n/fr-FR.json} → locale {@code fr-FR}, family {@code i18n/|.json}</li>
 * </ul>
 */
@Slf4j
public final class I18nFileParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Trailing locale token: a 2-letter language, optionally followed by
     * {@code _}/{@code -} segments for region (2-3 uppercase letters, or 3
     * digits), script (4 letters, title-case), or variant (alphanumeric
     * starting with uppercase or digit). Anchored at the end of the file stem.
     *
     * <p>Segments after the language MUST start with uppercase or digit so that
     * non-locale suffixes ({@code release-notes}, {@code extra}, {@code backup})
     * are never misclassified as locale tokens.</p>
     */
    private static final Pattern LOCALE_SUFFIX = Pattern.compile(
            "^(?<base>.*?)(?<sep>[._-])(?<locale>[a-z]{2}(?:[_-][A-Z0-9][A-Za-z0-9]*)*)$");

    /** A stem that is itself just a locale token (e.g. {@code en}, {@code fr-FR}). */
    private static final Pattern BARE_LOCALE = Pattern.compile(
            "^[a-z]{2}(?:[_-][A-Z0-9][A-Za-z0-9]*)*$");

    private I18nFileParser() {
    }

    /** Immutable description of one parsed locale file. */
    public record LocaleFile(String path, String familyId, String locale,
                             Map<String, String> entries) {
        public LocaleFile {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }
    }

    /**
     * Parses {@code content} for the given workspace-relative {@code path},
     * dispatching on the file extension. Returns a {@link LocaleFile} carrying
     * the flat ordered entries plus the resolved family id and locale token.
     */
    public static LocaleFile parse(String path, String content) {
        Map<String, String> entries = I18nPathGuard.isJson(path)
                ? parseJson(content)
                : parseProperties(content);
        String stem = stem(path);
        String dir = dir(path);
        String ext = I18nPathGuard.isJson(path) ? ".json" : ".properties";

        String base;
        String locale;
        Matcher m = LOCALE_SUFFIX.matcher(stem);
        if (m.matches()) {
            base = m.group("base");
            locale = m.group("locale");
        } else if (BARE_LOCALE.matcher(stem).matches()) {
            base = "";
            locale = stem;
        } else {
            // No recognisable locale token — treat the whole stem as the base
            // and the implicit default locale.
            base = stem;
            locale = "";
        }
        String familyId = dir + base + "|" + ext;
        return new LocaleFile(normalizePath(path), familyId, normalizeLocale(locale), entries);
    }

    /** Normalises a locale token to lower-case language + upper-case region using {@code _}. */
    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "";
        }
        String[] parts = locale.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            sb.append('_').append(parts[i].toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Parses a Java {@code .properties} file into an ordered {@code key → value}
     * map, following {@link java.util.Properties#load} semantics:
     *
     * <ul>
     *   <li>Line continuation: a physical line ending with an odd number of
     *       {@code \\} characters merges with the next physical line (the
     *       trailing {@code \\} is dropped on the merged line).</li>
     *   <li>Comments: blank lines and lines whose first non-whitespace character
     *       is {@code #} or {@code !} are ignored.</li>
     *   <li>Separator: the first unescaped {@code =}, {@code :}, or whitespace
     *       character ends the key. Whitespace after the separator is skipped to
     *       find the value start.</li>
     *   <li>Key unescaping: {@code \\t}, {@code \\n}, {@code \\r}, {@code \\f},
     *       {@code \\\\}, and {@code \\uXXXX} sequences in keys are resolved.
     *       Escaped separators ({@code \\=}, {@code \\:}, {@code \\ }) become
     *       literal characters in the key.</li>
     *   <li>Values are stored raw (no unescaping), matching the
     *       {@code Properties} contract — only leading whitespace after the
     *       separator is stripped.</li>
     * </ul>
     */
    // Visible for testing
    static Map<String, String> parseProperties(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] physicalLines = content.split("\\r\\n|\\r|\\n");
        int i = 0;
        while (i < physicalLines.length) {
            String logical = assembleLogicalLine(physicalLines, i);
            i += countPhysicalLinesConsumed(physicalLines, i);

            // Strip leading whitespace from the logical line.
            int firstChar = 0;
            int len = logical.length();
            while (firstChar < len && isWhitespace(logical.charAt(firstChar))) {
                firstChar++;
            }
            if (firstChar >= len) {
                continue; // blank line
            }
            char c0 = logical.charAt(firstChar);
            if (c0 == '#' || c0 == '!') {
                continue; // comment
            }

            int sep = findSeparator(logical, firstChar);
            String key;
            String value;
            if (sep < 0) {
                // No separator — entire logical line is the key, value is empty.
                key = loadConvert(logical.substring(firstChar));
                value = "";
            } else {
                key = loadConvert(logical.substring(firstChar, sep));
                // Skip whitespace right after the separator.
                int valStart = sep + 1;
                while (valStart < len && isWhitespace(logical.charAt(valStart))) {
                    valStart++;
                }
                value = valStart < len ? logical.substring(valStart) : "";
            }
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Assembles one logical line from one or more physical lines joined by
     * the line-continuation convention: when a physical line ends with an odd
     * number of {@code \\}, the trailing {@code \\} is dropped and the next
     * physical line is appended.
     */
    private static String assembleLogicalLine(String[] physicalLines, int startIdx) {
        StringBuilder sb = new StringBuilder(physicalLines[startIdx]);
        int idx = startIdx;
        while (idx < physicalLines.length && endsWithOddBackslashes(sb.toString())) {
            sb.setLength(sb.length() - 1); // drop the trailing continuation backslash
            idx++;
            if (idx < physicalLines.length) {
                sb.append(physicalLines[idx]);
            }
        }
        return sb.toString();
    }

    /** Returns the number of physical lines consumed to form one logical line. */
    private static int countPhysicalLinesConsumed(String[] physicalLines, int startIdx) {
        int count = 1;
        int idx = startIdx;
        String current = physicalLines[startIdx];
        while (idx < physicalLines.length && endsWithOddBackslashes(current)) {
            count++;
            idx++;
            if (idx < physicalLines.length) {
                current = physicalLines[idx];
            }
        }
        return count;
    }

    private static boolean endsWithOddBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    /**
     * Finds the index of the first unescaped {@code =}, {@code :}, or
     * whitespace character in {@code logical}, starting from {@code offset}.
     * Returns {@code -1} if no separator is found.
     */
    private static int findSeparator(String logical, int offset) {
        for (int i = offset; i < logical.length(); i++) {
            char c = logical.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
                if (i >= logical.length()) {
                    break;
                }
                continue;
            }
            if (c == '=' || c == ':' || isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    /**
     * Replicates {@link java.util.Properties#loadConvert} unescaping for keys:
     * {@code \\t} → tab, {@code \\n} → newline, {@code \\r} → CR, {@code \\f}
     * → form-feed, {@code \\uXXXX} → Unicode code-point, {@code \\} followed by
     * any other character → that character (literal {@code =}, {@code :}, etc.).
     */
    // Visible for testing
    static String loadConvert(String key) {
        int len = key.length();
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = key.charAt(i + 1);
                switch (next) {
                    case 't' -> { out.append('\t'); i++; }
                    case 'n' -> { out.append('\n'); i++; }
                    case 'r' -> { out.append('\r'); i++; }
                    case 'f' -> { out.append('\f'); i++; }
                    case 'u'  -> {
                        // backslash-uXXXX — up to 4 hex digits
                        int codePoint = 0;
                        int j;
                        for (j = i + 2; j < i + 6 && j < len; j++) {
                            char hc = key.charAt(j);
                            int digit = Character.digit(hc, 16);
                            if (digit < 0) {
                                break;
                            }
                            codePoint = (codePoint << 4) | digit;
                        }
                        if (j > i + 2) {
                            out.appendCodePoint(codePoint);
                            i = j - 1;
                        } else {
                            out.append('u'); // malformed backslash-u, emit literally
                            i++;
                        }
                    }
                    default -> {
                        out.append(next);
                        i++;
                    }
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Returns {@code true} when the JSON content has at least one non-scalar
     * value at the root level — i.e. the root object contains nested objects
     * or arrays. Only flat {@code {"key": "value"}} shapes are safe for the
     * agent to round-trip (the write path has no structured JSON emitter).
     */
    // Visible for testing
    static boolean isNestedJson(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(content);
            if (!root.isObject()) {
                return true; // array root is inherently nested for i18n purposes
            }
            for (JsonNode child : root) {
                if (child.isObject() || child.isArray()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.debug("i18n-coverage: could not parse JSON to check nesting: {}", e.getMessage());
            return false; // unparseable — let detection fail downstream
        }
    }

    static Map<String, String> parseJson(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(content);
            flatten("", root, out);
        } catch (RuntimeException e) {
            log.debug("i18n-coverage: could not parse JSON translation file: {}", e.getMessage());
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, "");
            }
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.isString() ? node.asString() : node.toString());
        }
    }

    static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String stem(String path) {
        String p = normalizePath(path);
        int slash = p.lastIndexOf('/');
        String file = slash < 0 ? p : p.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static String dir(String path) {
        String p = normalizePath(path);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? "" : p.substring(0, slash + 1);
    }
}
