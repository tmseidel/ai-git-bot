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
     * Trailing locale token: a 2-letter language, optionally followed by a
     * {@code _}/{@code -} region (2 letters) and/or a variant. Anchored at the
     * end of the file stem.
     */
    private static final Pattern LOCALE_SUFFIX = Pattern.compile(
            "^(?<base>.*?)(?<sep>[._-])(?<locale>[a-z]{2}(?:[_-][A-Za-z]{2,})*)$");

    /** A stem that is itself just a locale token (e.g. {@code en}, {@code fr-FR}). */
    private static final Pattern BARE_LOCALE = Pattern.compile(
            "^[a-z]{2}(?:[_-][A-Za-z]{2,})*$");

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

    static Map<String, String> parseProperties(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String rawLine : content.split("\r\n|\r|\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int sep = firstSeparator(line);
            if (sep < 0) {
                continue;
            }
            String key = line.substring(0, sep).strip();
            String value = line.substring(sep + 1).strip();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static int firstSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
                continue;
            }
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
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
