package org.remus.giteabot.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Renders artifact payloads (test screenshots, traces, log files, …) into a
 * provider-agnostic Markdown comment body. Used by the default
 * {@link RepositoryApiClient#attachPullRequestArtifact(String, String, Long, String, String, byte[])}
 * implementation and reused by per-provider overrides that still want the same
 * presentation for small inline artifacts.
 *
 * <p>Rendering decisions:</p>
 * <ul>
 *     <li><strong>Images</strong> (content type {@code image/*}, ≤ {@value #INLINE_IMAGE_MAX_BYTES} bytes) →
 *         Markdown image with a {@code data:} URI.</li>
 *     <li><strong>Text</strong> (content type {@code text/*}, {@code application/json},
 *         {@code application/xml} or absent + UTF-8-decodable, ≤ {@value #INLINE_TEXT_MAX_BYTES} bytes) →
 *         fenced code block with a language hint inferred from the file extension.</li>
 *     <li><strong>Everything else / oversized</strong> → header with size and SHA-256 so reviewers
 *         can correlate with the runner log; no payload bytes.</li>
 * </ul>
 *
 * <p>The class is deliberately stateless and free of Spring dependencies so it
 * can be reused by tests, by per-provider {@link RepositoryApiClient}
 * implementations and by the PR-workflow {@code attach-artifact} tool without
 * pulling in the application context.</p>
 */
public final class ArtifactCommentRenderer {

    /** Hard cap for inline image data URIs. GitHub rejects comments above ~65 KiB; keep headroom. */
    public static final int INLINE_IMAGE_MAX_BYTES = 48 * 1024;
    /** Hard cap for inline text fences (matches GitHub comment limit minus markdown overhead). */
    public static final int INLINE_TEXT_MAX_BYTES = 60 * 1024;

    private ArtifactCommentRenderer() {
        // utility
    }

    /** Result of {@link #render(String, String, byte[])}. */
    public record RenderedComment(String markdown, RenderMode mode) { }

    /** How the renderer chose to present the payload. */
    public enum RenderMode {
        INLINE_IMAGE,
        INLINE_TEXT,
        SUMMARY_ONLY
    }

    public static RenderedComment render(String fileName, String contentType, byte[] payload) {
        String safeName = fileName == null || fileName.isBlank() ? "artifact" : fileName.trim();
        byte[] safePayload = payload == null ? new byte[0] : payload;
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();

        if (looksLikeImage(mime, safeName) && safePayload.length <= INLINE_IMAGE_MAX_BYTES) {
            String dataUri = "data:" + (mime.isEmpty() ? "image/png" : mime)
                    + ";base64," + Base64.getEncoder().encodeToString(safePayload);
            String md = "**" + escapeMd(safeName) + "** (" + humanBytes(safePayload.length) + ")\n\n"
                    + "![" + escapeMd(safeName) + "](" + dataUri + ")\n";
            return new RenderedComment(md, RenderMode.INLINE_IMAGE);
        }
        if (looksLikeText(mime, safeName, safePayload) && safePayload.length <= INLINE_TEXT_MAX_BYTES) {
            String body = new String(safePayload, StandardCharsets.UTF_8);
            String fence = languageHint(safeName);
            String md = "**" + escapeMd(safeName) + "** (" + humanBytes(safePayload.length) + ")\n\n"
                    + "```" + fence + "\n" + body + (body.endsWith("\n") ? "" : "\n") + "```\n";
            return new RenderedComment(md, RenderMode.INLINE_TEXT);
        }
        String md = "**" + escapeMd(safeName) + "** (" + humanBytes(safePayload.length) + ")\n\n"
                + "Artifact too large or not inlineable. SHA-256: `" + sha256(safePayload) + "`\n";
        return new RenderedComment(md, RenderMode.SUMMARY_ONLY);
    }

    private static boolean looksLikeImage(String mime, String fileName) {
        if (mime.startsWith("image/")) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private static boolean looksLikeText(String mime, String fileName, byte[] payload) {
        if (mime.startsWith("text/")
                || mime.equals("application/json")
                || mime.equals("application/xml")
                || mime.equals("application/x-yaml")) {
            return true;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".html")) {
            return true;
        }
        // Heuristic: only treat as text when the leading window is valid UTF-8 with no NULs.
        if (mime.isEmpty() && payload.length > 0) {
            int probe = Math.min(payload.length, 512);
            for (int i = 0; i < probe; i++) {
                if (payload[i] == 0) return false;
            }
            try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(payload, 0, probe));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static String languageHint(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return "";
        return switch (lower.substring(dot + 1)) {
            case "json" -> "json";
            case "xml"  -> "xml";
            case "yml", "yaml" -> "yaml";
            case "md"   -> "markdown";
            case "html" -> "html";
            case "ts"   -> "ts";
            case "js"   -> "js";
            case "java" -> "java";
            case "py"   -> "python";
            case "log", "txt" -> "";
            default -> "";
        };
    }

    private static String humanBytes(int bytes) {
        return ArtifactUploadSupport.humanBytes(bytes);
    }

    private static String sha256(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            return "n/a";
        }
    }

    private static String escapeMd(String s) {
        return s.replace("`", "\\`").replace("|", "\\|");
    }
}
