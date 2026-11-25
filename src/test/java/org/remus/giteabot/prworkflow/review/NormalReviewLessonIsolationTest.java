package org.remus.giteabot.prworkflow.review;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.CodeReviewService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normal {@code review} never reaches lesson retrieval: none of its classes holds, receives or
 * returns anything from the learning packages, so no shared configuration can route a lesson —
 * or a retrieval call — into it. Agentic review gets lessons only through its own explicit
 * factory argument.
 */
class NormalReviewLessonIsolationTest {

    private static final String LEARNING = "org.remus.giteabot.learning.";

    @Test
    void theNormalReviewPathHasNoDependencyOnLearning() {
        for (Class<?> type : List.of(ReviewWorkflow.class, CodeReviewService.class,
                CodeReviewServiceFactory.class)) {
            assertThat(learningTypesOf(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    private static List<String> learningTypesOf(Class<?> type) {
        List<Class<?>> used = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            used.add(field.getType());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            used.addAll(List.of(constructor.getParameterTypes()));
        }
        for (Method method : type.getDeclaredMethods()) {
            used.add(method.getReturnType());
            used.addAll(List.of(method.getParameterTypes()));
        }
        return used.stream().map(Class::getName).filter(name -> name.startsWith(LEARNING))
                .distinct().toList();
    }
}
