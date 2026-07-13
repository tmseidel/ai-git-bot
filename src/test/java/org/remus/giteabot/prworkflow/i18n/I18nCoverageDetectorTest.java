package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class I18nCoverageDetectorTest {

    private final List<String> patterns = List.of("i18n/*.properties", "i18n/*.json");

    private void write(Path ws, String rel, String content) throws IOException {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    void detectsMissingKeys_properties(@TempDir Path ws) throws IOException {
        write(ws, "i18n/messages_en.properties", "greeting=Hello\nfarewell=Bye\nnew.key=Added");
        write(ws, "i18n/messages_de.properties", "greeting=Hallo\nfarewell=Tschüss");

        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");

        assertThat(report.hasGaps()).isTrue();
        assertThat(report.totalMissingKeys()).isEqualTo(1);
        I18nCoverageDetector.LocaleGap gap = report.families().getFirst().gaps().getFirst();
        assertThat(gap.locale()).isEqualTo("de");
        assertThat(gap.missingKeys()).containsExactly("new.key");
        assertThat(gap.staleKeys()).isEmpty();
    }

    @Test
    void detectsStaleKeys_properties(@TempDir Path ws) throws IOException {
        write(ws, "i18n/messages_en.properties", "greeting=Hello");
        write(ws, "i18n/messages_de.properties", "greeting=Hallo\nremoved.key=Weg");

        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");

        assertThat(report.hasGaps()).isTrue();
        assertThat(report.totalStaleKeys()).isEqualTo(1);
        I18nCoverageDetector.LocaleGap gap = report.families().getFirst().gaps().getFirst();
        assertThat(gap.staleKeys()).containsExactly("removed.key");
        assertThat(gap.missingKeys()).isEmpty();
    }

    @Test
    void detectsMissingAndStaleKeys_json(@TempDir Path ws) throws IOException {
        write(ws, "i18n/en.json", "{\"a\":\"A\",\"b\":\"B\",\"c\":\"C\"}");
        write(ws, "i18n/fr.json", "{\"a\":\"A-fr\",\"z\":\"Z-fr\"}");

        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");

        assertThat(report.hasGaps()).isTrue();
        I18nCoverageDetector.LocaleGap gap = report.families().getFirst().gaps().getFirst();
        assertThat(gap.locale()).isEqualTo("fr");
        assertThat(gap.missingKeys()).containsExactly("b", "c");
        assertThat(gap.staleKeys()).containsExactly("z");
    }

    @Test
    void noGapsWhenFullyCovered(@TempDir Path ws) throws IOException {
        write(ws, "i18n/messages_en.properties", "greeting=Hello");
        write(ws, "i18n/messages_de.properties", "greeting=Hallo");

        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");
        assertThat(report.hasGaps()).isFalse();
    }

    @Test
    void baselineDefaultsToSuffixlessWhenConfiguredMissing(@TempDir Path ws) throws IOException {
        write(ws, "i18n/messages.properties", "a=1\nb=2");
        write(ws, "i18n/messages_de.properties", "a=1");

        // Configured baseline "en" does not exist → fall back to the implicit-default file.
        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");

        assertThat(report.families()).hasSize(1);
        assertThat(report.families().getFirst().baselineLocale()).isEmpty();
        assertThat(report.totalMissingKeys()).isEqualTo(1);
    }

    @Test
    void singleFileFamilyProducesNoGap(@TempDir Path ws) throws IOException {
        write(ws, "i18n/en.json", "{\"a\":\"A\"}");
        I18nCoverageDetector.Report report = I18nCoverageDetector.detect(ws, patterns, "en");
        assertThat(report.hasGaps()).isFalse();
    }
}
