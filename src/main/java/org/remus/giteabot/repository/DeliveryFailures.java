package org.remus.giteabot.repository;

import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Tells a definite refusal of a Git write apart from an outcome the provider may still have applied.
 * <p>
 * Decided by the HTTP status where the provider answered: a 4xx is a refusal, while a 5xx — a
 * gateway error in front of a provider that accepted the write included — is uncertain. No answer
 * at all (a timeout or a dropped connection, surfaced as an {@link IOException} or a
 * {@link TimeoutException}) is uncertain too. Exception messages are never inspected: their
 * wording differs per provider and proxy.
 */
public final class DeliveryFailures {

    private DeliveryFailures() {
    }

    /** {@code true} when retrying the same write cannot duplicate it. */
    public static boolean isDefiniteRefusal(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RestClientResponseException response) {
                return response.getStatusCode().is4xxClientError();
            }
            if (current instanceof IOException || current instanceof TimeoutException) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }
}
