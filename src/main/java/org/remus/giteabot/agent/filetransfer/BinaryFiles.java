package org.remus.giteabot.agent.filetransfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class BinaryFiles {

    private static final int SNIFF_BYTES = 8_192;

    private BinaryFiles() {
    }

    public record Info(long size, String mimeType, String sha256) { }

    public static Info describe(Path file) throws IOException {
        return new Info(Files.size(file), detectMimeType(file), sha256(file));
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String detectMimeType(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SNIFF_BYTES);
        }
        return detectMimeType(head);
    }

    static String detectMimeType(byte[] b) {
        if (startsWith(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWithText(b, 0, "GIF87a") || startsWithText(b, 0, "GIF89a")) return "image/gif";
        if (startsWithText(b, 0, "RIFF") && b.length >= 12) {
            if (startsWithText(b, 8, "WEBP")) return "image/webp";
            if (startsWithText(b, 8, "WAVE")) return "audio/wav";
            if (startsWithText(b, 8, "AVI ")) return "video/x-msvideo";
        }
        if (startsWithText(b, 0, "%PDF-")) return "application/pdf";
        if (startsWith(b, 0, 'P', 'K', 0x03, 0x04)) return "application/zip";
        if (startsWith(b, 0, 0x1F, 0x8B)) return "application/gzip";
        if (startsWithText(b, 0, "OggS")) return "audio/ogg";
        if (startsWithText(b, 0, "fLaC")) return "audio/flac";
        if (startsWithText(b, 0, "ID3")) return "audio/mpeg";
        if (startsWithText(b, 4, "ftyp") && b.length >= 12) {
            String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
            return switch (brand) {
                case "qt  " -> "video/quicktime";
                case "M4A " -> "audio/mp4";
                case "avif", "avis" -> "image/avif";
                case "heic", "heix", "mif1" -> "image/heic";
                default -> "video/mp4";
            };
        }
        if (startsWith(b, 0, 0x1A, 0x45, 0xDF, 0xA3)) return "video/webm";
        if (startsWithText(b, 0, "wOFF")) return "font/woff";
        if (startsWithText(b, 0, "wOF2")) return "font/woff2";
        if (startsWithText(b, 0, "OTTO")) return "font/otf";
        if (startsWith(b, 0, 0x00, 0x01, 0x00, 0x00, 0x00)) return "font/ttf";
        if (startsWithText(b, 0, "BM")) return "image/bmp";
        if (startsWith(b, 0, 'I', 'I', 0x2A, 0x00) || startsWith(b, 0, 'M', 'M', 0x00, 0x2A)) return "image/tiff";
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "audio/mpeg";
        if (isUtf8Text(b)) {
            String text = new String(b, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if (text.contains("<svg") && (text.startsWith("<svg") || text.startsWith("<?xml"))) {
                return "image/svg+xml";
            }
            return "text/plain";
        }
        return "application/octet-stream";
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isUtf8Text(byte[] b) {
        for (byte value : b) {
            if (value == 0) {
                return false;
            }
        }
        // endOfInput=false: a prefix cut inside a multi-byte character is underflow, not an error.
        CoderResult result = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b), CharBuffer.allocate(b.length), false);
        return !result.isError();
    }

    private static boolean startsWith(byte[] b, int offset, int... expected) {
        if (b.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((b[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithText(byte[] b, int offset, String expected) {
        return startsWith(b, offset, expected.chars().toArray());
    }
}
