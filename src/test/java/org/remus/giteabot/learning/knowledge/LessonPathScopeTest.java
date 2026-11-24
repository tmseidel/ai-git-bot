package org.remus.giteabot.learning.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LessonPathScopeTest {

    @Test
    void patternsAreNormalizedAndDeduplicated() {
        assertThat(LessonPathScope.parse(" ./src/main/** ,docs/*.md src/main/**"))
                .containsExactly("src/main/**", "docs/*.md");
    }

    @Test
    void doubleStarAloneMeansTheWholeRepository() {
        assertThat(LessonPathScope.parse("**")).isEmpty();
        assertThat(LessonPathScope.serialize(List.of())).isNull();
        assertThat(LessonPathScope.deserialize(null)).isEmpty();
    }

    @Test
    void storedFormRoundTrips() {
        List<String> patterns = List.of("src/**", "pom.xml");
        assertThat(LessonPathScope.deserialize(LessonPathScope.serialize(patterns)))
                .isEqualTo(patterns);
    }

    @Test
    void patternsThatLeaveTheRepositoryOrCannotMatchAreRefused() {
        for (String bad : List.of("../x", "src/../../x", "/abs/**", "C:/x", "src\\x", "[a-", "")) {
            assertThatThrownBy(() -> LessonPathScope.parse(bad))
                    .as(bad).isInstanceOf(LessonRejectedException.class);
        }
        assertThatThrownBy(() -> LessonPathScope.parse("x".repeat(LessonPathScope.MAX_PATTERN_CHARS + 1)))
                .isInstanceOf(LessonRejectedException.class);
    }

    @Test
    void theNumberOfPatternsIsBounded() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= LessonPathScope.MAX_PATTERNS; i++) {
            many.append("dir").append(i).append("/** ");
        }
        assertThatThrownBy(() -> LessonPathScope.parse(many.toString()))
                .isInstanceOf(LessonRejectedException.class);
    }

    @Test
    void wideningIsDetected() {
        assertThat(LessonPathScope.expands(List.of("src/**"), List.of())).isTrue();
        assertThat(LessonPathScope.expands(List.of("src/a/**"), List.of("src/a/**", "src/b/**")))
                .isTrue();
        assertThat(LessonPathScope.expands(List.of(), List.of("src/**"))).isFalse();
        assertThat(LessonPathScope.expands(List.of("src/a/**", "src/b/**"), List.of("src/a/**")))
                .isFalse();
    }
}
