package org.remus.giteabot.eventhook;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventHookEndpointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventHookEndpoint endpoint(String eventTypes) {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setEnabled(true);
        endpoint.setEventTypes(eventTypes);
        return endpoint;
    }

    // --- subscription parsing ---

    @Test
    void subscribedEventTypes_parsesCommaSeparatedNames() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED, ISSUE_ASSIGNMENT_FAILED");

        assertEquals(Set.of(EventHookEventType.PR_WORKFLOW_STARTED,
                EventHookEventType.ISSUE_ASSIGNMENT_FAILED), endpoint.subscribedEventTypes());
    }

    @Test
    void subscribedEventTypes_toleratesWhitespaceAndEmptySegments() {
        EventHookEndpoint endpoint = endpoint("  PR_WORKFLOW_STARTED ,,PR_WORKFLOW_FAILED,");

        assertEquals(Set.of(EventHookEventType.PR_WORKFLOW_STARTED,
                EventHookEventType.PR_WORKFLOW_FAILED), endpoint.subscribedEventTypes());
    }

    @Test
    void subscribedEventTypes_blankOrNull_returnsEmpty() {
        assertTrue(endpoint(null).subscribedEventTypes().isEmpty());
        assertTrue(endpoint("   ").subscribedEventTypes().isEmpty());
    }

    @Test
    void subscribedEventTypes_unknownName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> endpoint("PR_WORKFLOW_STARTED,NOPE").subscribedEventTypes());
    }

    @Test
    void isSubscribedTo_enabledAndListed_returnsTrue() {
        assertTrue(endpoint("PR_WORKFLOW_STARTED").isSubscribedTo(EventHookEventType.PR_WORKFLOW_STARTED));
    }

    @Test
    void isSubscribedTo_notListed_returnsFalse() {
        assertFalse(endpoint("PR_WORKFLOW_STARTED").isSubscribedTo(EventHookEventType.PR_WORKFLOW_FAILED));
    }

    @Test
    void isSubscribedTo_disabled_returnsFalse() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setEnabled(false);

        assertFalse(endpoint.isSubscribedTo(EventHookEventType.PR_WORKFLOW_STARTED));
    }

    // --- scope matching ---

    @Test
    void matchesScope_globalEndpoint_matchesEverything() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");

        assertTrue(endpoint.matchesScope(1L, "acme", "shop"));
        assertTrue(endpoint.matchesScope(null, null, null));
    }

    @Test
    void matchesScope_botScoped_matchesOnlyThatBot() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setBotId(3L);

        assertTrue(endpoint.matchesScope(3L, "acme", "shop"));
        assertFalse(endpoint.matchesScope(4L, "acme", "shop"));
        assertFalse(endpoint.matchesScope(null, "acme", "shop"));
    }

    @Test
    void matchesScope_repoScoped_matchesCaseInsensitively() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setRepoOwner("Acme");
        endpoint.setRepoName("Shop");

        assertTrue(endpoint.matchesScope(null, "acme", "shop"));
        assertFalse(endpoint.matchesScope(null, "other", "shop"));
        assertFalse(endpoint.matchesScope(null, "acme", "other"));
    }

    @Test
    void matchesScope_botAndRepoScoped_requiresAllToMatch() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setBotId(3L);
        endpoint.setRepoOwner("acme");
        endpoint.setRepoName("shop");

        assertTrue(endpoint.matchesScope(3L, "acme", "shop"));
        assertFalse(endpoint.matchesScope(3L, "acme", "other"));
        assertFalse(endpoint.matchesScope(4L, "acme", "shop"));
    }

    @Test
    void matchesScope_emptyOrBlankRepoScope_treatedAsGlobal() {
        // The admin form binds cleared scope inputs as "" (not null) — these
        // must behave like an unset scope instead of matching nothing.
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setRepoOwner("");
        endpoint.setRepoName("   ");

        assertTrue(endpoint.matchesScope(null, "acme", "shop"));
        assertTrue(endpoint.matchesScope(3L, "other", "other"));
        assertTrue(endpoint.matchesScope(null, null, null));
    }

    @Test
    void matchesScope_blankOwnerWithSetName_nameStillRestricts() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");
        endpoint.setRepoOwner("");
        endpoint.setRepoName("shop");

        assertTrue(endpoint.matchesScope(null, "acme", "shop"));
        assertFalse(endpoint.matchesScope(null, "acme", "other"));
    }

    // --- custom headers ---

    @Test
    void parsedCustomHeaders_validJson_returnsMap() {
        assertEquals(Map.of("X-Team", "infra", "X-Env", "prod"),
                endpoint("PR_WORKFLOW_STARTED").parsedCustomHeaders(objectMapper,
                        "{\"X-Team\":\"infra\",\"X-Env\":\"prod\"}"));
    }

    @Test
    void parsedCustomHeaders_nullOrBlank_returnsEmpty() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");

        assertTrue(endpoint.parsedCustomHeaders(objectMapper, null).isEmpty());
        assertTrue(endpoint.parsedCustomHeaders(objectMapper, "  ").isEmpty());
    }

    @Test
    void parsedCustomHeaders_invalidJson_returnsEmpty() {
        EventHookEndpoint endpoint = endpoint("PR_WORKFLOW_STARTED");

        assertTrue(endpoint.parsedCustomHeaders(objectMapper, "{not json").isEmpty());
    }
}
