package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.event.model.WebhookPayload;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which {@link BotWebhookService} methods run off the webhook thread.
 * <p>
 * The webhook dispatcher and the internal dispatchers call these methods on the injected Spring proxy,
 * so the annotation position is behaviour, not decoration:
 * <ul>
 *   <li>An entry point without {@code @Async} blocks the provider's HTTP response, which then times
 *       out and is redelivered.</li>
 *   <li>{@code tryHandleLearningCommand} must stay synchronous — callers branch on its return value,
 *       and the async proxy answers {@code null} for a primitive, which surfaces as
 *       {@code AopInvocationException} in the caller.</li>
 * </ul>
 * The dispatcher tests mock this service, so they cannot see either mistake; these assertions read the
 * class itself.
 */
class BotWebhookServiceAsyncContractTest {

    private static Method method(String name, Class<?>... parameters) {
        return Arrays.stream(BotWebhookService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), parameters))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BotWebhookService." + name + " not found"));
    }

    @Test
    void theLearningCommandCheckIsSynchronousSoItsResultCanBeUsed() {
        Method check = method("tryHandleLearningCommand", Bot.class, WebhookPayload.class,
                String.class, String.class);

        assertThat(check.isAnnotationPresent(Async.class)).isFalse();
        assertThat(check.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    void everyAsyncMethodReturnsVoid() {
        List<String> offenders = Arrays.stream(BotWebhookService.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(Async.class))
                .filter(candidate -> candidate.getReturnType() != void.class)
                .map(Method::getName)
                .toList();

        assertThat(offenders)
                .as("@Async methods whose result the async proxy cannot deliver")
                .isEmpty();
    }

    @Test
    void theWebhookEntryPointsAreAllAsync() {
        List<Method> entryPoints = List.of(
                method("reviewPullRequest", Bot.class, WebhookPayload.class),
                method("handlePrComment", Bot.class, WebhookPayload.class),
                method("handleInlineComment", Bot.class, WebhookPayload.class),
                method("handleReviewSubmitted", Bot.class, WebhookPayload.class),
                method("handleIssueAssigned", Bot.class, WebhookPayload.class),
                method("handleIssueCreated", Bot.class, WebhookPayload.class),
                method("handleIssueComment", Bot.class, WebhookPayload.class));

        assertThat(entryPoints.stream()
                .filter(candidate -> !candidate.isAnnotationPresent(Async.class))
                .map(Method::getName)
                .toList())
                .as("entry points that would block the webhook request thread")
                .isEmpty();
    }
}
