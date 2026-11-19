package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.event.WebhookEventKind;
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
 * so the annotation position is behaviour, not decoration: an entry point without {@code @Async} blocks
 * the provider's HTTP response, which then times out and is redelivered. The dispatcher tests mock this
 * service, so they cannot see that mistake; this assertion reads the class itself.
 * <p>
 * The opposite drift — {@code @Async} landing on a method whose result the caller needs, such as
 * {@code tryHandleLearningCommand} — is caught for every class by
 * {@code ArchitectureTest#async_methods_return_void_or_a_future}.
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
    void theWebhookEntryPointsAreAllAsync() {
        List<Method> entryPoints = List.of(
                method("reviewPullRequest", Bot.class, WebhookPayload.class, WebhookEventKind.class),
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
