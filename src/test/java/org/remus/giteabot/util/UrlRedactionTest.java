package org.remus.giteabot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlRedactionTest {

    @Test
    void redactStripsQueryAndFragment() {
        assertThat(UrlRedaction.redact("https://h.example/a.png?X-Amz-Signature=s3cr3t#frag"))
                .isEqualTo("https://h.example/a.png");
        assertThat(UrlRedaction.redact("https://h.example/a.png#token=s3cr3t")).isEqualTo("https://h.example/a.png");
        assertThat(UrlRedaction.redact("https://h.example/a.png")).isEqualTo("https://h.example/a.png");
    }

    @Test
    void redactAllMasksEveryUrlInText() {
        String text = "upload-file inputs/ref.jpg https://up.example/put?sig=s3cr3t PUT and `https://b.example/x?k=v`";

        assertThat(UrlRedaction.redactAll(text))
                .isEqualTo("upload-file inputs/ref.jpg https://up.example/put PUT and `https://b.example/x`");
    }

    @Test
    void redactAllLeavesTextWithoutUrlsAlone() {
        assertThat(UrlRedaction.redactAll("why? because#reasons")).isEqualTo("why? because#reasons");
    }
}
