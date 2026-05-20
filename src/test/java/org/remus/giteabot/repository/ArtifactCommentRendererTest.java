package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactCommentRendererTest {

    @Test
    void inlinesSmallPngAsDataUri() {
        // 1x1 PNG header bytes (not a real valid PNG, but the renderer doesn't decode).
        byte[] payload = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

        ArtifactCommentRenderer.RenderedComment out =
                ArtifactCommentRenderer.render("screenshot.png", "image/png", payload);

        assertThat(out.mode()).isEqualTo(ArtifactCommentRenderer.RenderMode.INLINE_IMAGE);
        assertThat(out.markdown())
                .contains("**screenshot.png**")
                .contains("data:image/png;base64,")
                .contains("![screenshot.png](data:image/png;base64,");
    }

    @Test
    void inlinesSmallTextAsFencedCodeBlock() {
        byte[] payload = "line one\nline two\n".getBytes(StandardCharsets.UTF_8);

        ArtifactCommentRenderer.RenderedComment out =
                ArtifactCommentRenderer.render("trace.log", "text/plain", payload);

        assertThat(out.mode()).isEqualTo(ArtifactCommentRenderer.RenderMode.INLINE_TEXT);
        assertThat(out.markdown())
                .contains("**trace.log**")
                .contains("```\nline one\nline two\n```");
    }

    @Test
    void emitsLanguageHintForJson() {
        byte[] payload = "{\"k\":1}".getBytes(StandardCharsets.UTF_8);

        ArtifactCommentRenderer.RenderedComment out =
                ArtifactCommentRenderer.render("report.json", "application/json", payload);

        assertThat(out.markdown()).contains("```json\n");
    }

    @Test
    void fallsBackToSummaryForOversizedImage() {
        byte[] big = new byte[ArtifactCommentRenderer.INLINE_IMAGE_MAX_BYTES + 1];

        ArtifactCommentRenderer.RenderedComment out =
                ArtifactCommentRenderer.render("huge.png", "image/png", big);

        assertThat(out.mode()).isEqualTo(ArtifactCommentRenderer.RenderMode.SUMMARY_ONLY);
        assertThat(out.markdown())
                .contains("**huge.png**")
                .contains("SHA-256:");
        assertThat(out.markdown()).doesNotContain("data:image/png;base64,");
    }

    @Test
    void fallsBackToSummaryForBinaryWithoutContentType() {
        byte[] payload = new byte[]{0, 1, 2, 3, 0, (byte) 0xFF};

        ArtifactCommentRenderer.RenderedComment out =
                ArtifactCommentRenderer.render("blob.bin", null, payload);

        assertThat(out.mode()).isEqualTo(ArtifactCommentRenderer.RenderMode.SUMMARY_ONLY);
        assertThat(out.markdown()).contains("SHA-256:");
    }
}
