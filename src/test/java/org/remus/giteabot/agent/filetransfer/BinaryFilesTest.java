package org.remus.giteabot.agent.filetransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryFilesTest {

    @TempDir
    Path dir;

    @Test
    void detectsTypesFromLeadingBytes() {
        assertThat(BinaryFiles.detectMimeType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).isEqualTo("image/png");
        assertThat(BinaryFiles.detectMimeType(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(BinaryFiles.detectMimeType("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).isEqualTo("application/pdf");
        assertThat(BinaryFiles.detectMimeType("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("image/webp");
        assertThat(BinaryFiles.detectMimeType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8))).isEqualTo("image/svg+xml");
        assertThat(BinaryFiles.detectMimeType("hello wörld".getBytes(StandardCharsets.UTF_8))).isEqualTo("text/plain");
        assertThat(BinaryFiles.detectMimeType(bytes(0x00, 0x13, 0x37, 0xC3))).isEqualTo("application/octet-stream");
    }

    @Test
    void describeReturnsSizeTypeAndSha256() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "abc");

        BinaryFiles.Info info = BinaryFiles.describe(file);

        assertThat(info.size()).isEqualTo(3);
        assertThat(info.mimeType()).isEqualTo("text/plain");
        assertThat(info.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
