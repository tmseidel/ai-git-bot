package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactUploadSupportTest {

    @Test
    void humanBytes_formats() {
        assertEquals("0 B", ArtifactUploadSupport.humanBytes(0));
        assertEquals("1023 B", ArtifactUploadSupport.humanBytes(1023));
        assertEquals("1.0 KiB", ArtifactUploadSupport.humanBytes(1024));
        assertEquals("1.5 KiB", ArtifactUploadSupport.humanBytes(1536));
        assertEquals("2.0 MiB", ArtifactUploadSupport.humanBytes(2 * 1024 * 1024));
    }

    @Test
    void linkMarkdown_escapesPipesAndBackticks() {
        String md = ArtifactUploadSupport.linkMarkdown("trace|`x`.zip", "https://example.com/u/1/trace.zip");
        assertEquals("[trace\\|\\`x\\`.zip](https://example.com/u/1/trace.zip)", md);
    }

    @Test
    void linkMarkdown_defaultsFileNameWhenBlank() {
        String md = ArtifactUploadSupport.linkMarkdown("   ", "https://example.com/x");
        assertEquals("[artifact](https://example.com/x)", md);
    }

    @Test
    void linkMarkdown_handlesNullUrl() {
        String md = ArtifactUploadSupport.linkMarkdown("trace.zip", null);
        assertEquals("[trace.zip]()", md);
    }

    @Test
    void buildLinkComment_includesNameSizeAndLink() {
        String body = ArtifactUploadSupport.buildLinkComment(
                "trace.zip", 50_000, "[trace.zip](https://x/y/trace.zip)");
        assertTrue(body.contains("**trace.zip**"));
        assertTrue(body.contains("48.8 KiB"));
        assertTrue(body.contains("[trace.zip](https://x/y/trace.zip)"));
    }
}

