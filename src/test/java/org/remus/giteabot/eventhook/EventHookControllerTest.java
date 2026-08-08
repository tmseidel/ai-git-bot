package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC coverage for {@link EventHookController}: list rendering, save
 * validation (event types required, http(s) URL required), credential-free
 * save, enable/disable toggle and delete. Boots the full stack against H2,
 * matching {@code WorkflowConfigurationControllerMvcTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventHookControllerTest {

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private EventHookEndpointRepository endpointRepository;
    @Autowired
    private EventHookEndpointService endpointService;
    @Autowired
    private EventHookDeliveryRepository deliveryRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
        endpointRepository.deleteAll();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listRendersEndpoints() throws Exception {
        endpointRepository.save(endpoint("SIEM hook", "https://siem.example.com/hook"));

        mvc.perform(get("/admin/event-hooks"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SIEM hook")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://siem.example.com/hook")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("prworkflow.started")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveWithoutEventTypesIsRejected() throws Exception {
        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("name", "No types")
                        .param("url", "https://example.com/hook")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Select at least one event type")));

        assertTrue(endpointRepository.findAll().isEmpty(), "invalid save must not persist");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveWithNonHttpUrlIsRejected() throws Exception {
        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("name", "Bad URL")
                        .param("url", "ftp://example.com/hook")
                        .param("eventTypes", "PR_WORKFLOW_STARTED")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("URL must start with http:// or https://")));

        assertTrue(endpointRepository.findAll().isEmpty(), "invalid save must not persist");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveSucceedsWithNeitherCredential() throws Exception {
        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("name", "Plain hook")
                        .param("url", "https://example.com/hook")
                        .param("eventTypes", "PR_WORKFLOW_STARTED", "AGENT_REVIEW_FINDING_DETECTED")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks"));

        var saved = endpointRepository.findAll();
        assertEquals(1, saved.size());
        EventHookEndpoint endpoint = saved.getFirst();
        assertNotNull(endpoint.getId());
        assertNull(endpoint.getSecret(), "secret stays unset when no plaintext given");
        assertNull(endpoint.getAuthorizationHeader(), "authorization header stays unset when no plaintext given");
        assertTrue(endpoint.isSubscribedTo(EventHookEventType.PR_WORKFLOW_STARTED));
        assertTrue(endpoint.isSubscribedTo(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED));
        assertFalse(endpoint.isSubscribedTo(EventHookEventType.ISSUE_ASSIGNMENT_STARTED));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveIgnoresDirectBindingOfEncryptedFields() throws Exception {
        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("name", "Protected fields")
                        .param("url", "https://example.com/hook")
                        .param("eventTypes", "PR_WORKFLOW_STARTED")
                        .param("secret", "plain-secret")
                        .param("authorizationHeader", "Bearer token123456")
                        .param("customHeaders", "{\"X-Api-Key\":\"token123456\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks"));

        EventHookEndpoint endpoint = endpointRepository.findAll().getFirst();
        assertNull(endpoint.getSecret());
        assertNull(endpoint.getAuthorizationHeader());
        assertNull(endpoint.getCustomHeaders());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveWithPlainCustomHeadersEncryptsTheirStoredValue() throws Exception {
        String customHeaders = "{\"X-Api-Key\":\"token123456\"}";

        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("name", "Encrypted headers")
                        .param("url", "https://example.com/hook")
                        .param("eventTypes", "PR_WORKFLOW_STARTED")
                        .param("plainCustomHeaders", customHeaders))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks"));

        EventHookEndpoint endpoint = endpointRepository.findAll().getFirst();
        assertNotEquals(customHeaders, endpoint.getCustomHeaders());
        assertEquals(customHeaders, endpointService.decryptCustomHeaders(endpoint));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void toggleFlipsEnabledFlag() throws Exception {
        EventHookEndpoint endpoint = endpointRepository.save(endpoint("Toggle me", "https://example.com/hook"));
        assertTrue(endpoint.isEnabled());

        mvc.perform(post("/admin/event-hooks/{id}/toggle", endpoint.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertFalse(endpointRepository.findById(endpoint.getId()).orElseThrow().isEnabled());

        mvc.perform(post("/admin/event-hooks/{id}/toggle", endpoint.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertTrue(endpointRepository.findById(endpoint.getId()).orElseThrow().isEnabled());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRemovesEndpoint() throws Exception {
        EventHookEndpoint endpoint = endpointRepository.save(endpoint("Delete me", "https://example.com/hook"));

        mvc.perform(post("/admin/event-hooks/{id}/delete", endpoint.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertTrue(endpointRepository.findById(endpoint.getId()).isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deliveriesPageRendersRowsAndRetryButton() throws Exception {
        EventHookEndpoint endpoint = endpointRepository.save(endpoint("Delivery hook", "https://example.com/hook"));
        EventHookDelivery delivery = new EventHookDelivery();
        delivery.setDeliveryUuid("uuid-1");
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventType("prworkflow.started");
        delivery.setPayloadJson("{\"schemaVersion\":1}");
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setAttempts(5);
        delivery.setLastResponseCode(500);
        delivery.setLastError("connection refused");
        delivery.setCreatedAt(java.time.Instant.now());
        deliveryRepository.save(delivery);

        mvc.perform(get("/admin/event-hooks/{id}/deliveries", endpoint.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("prworkflow.started")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("connection refused")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retry")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void saveWithNonexistentIdIsRejected() throws Exception {
        mvc.perform(post("/admin/event-hooks/save").with(csrf())
                        .param("id", "99999")
                        .param("name", "Forged edit")
                        .param("url", "https://example.com/hook")
                        .param("eventTypes", "PR_WORKFLOW_STARTED")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("error", "Event hook endpoint not found"));

        assertTrue(endpointRepository.findAll().isEmpty(),
                "forged id must not persist a row via merge semantics");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void retryRedirectIsDerivedFromDeliveryNotCallerParam() throws Exception {
        EventHookEndpoint owner = endpointRepository.save(endpoint("Owner", "https://a.example.com/hook"));
        EventHookEndpoint other = endpointRepository.save(endpoint("Other", "https://b.example.com/hook"));
        EventHookDelivery delivery = new EventHookDelivery();
        delivery.setDeliveryUuid("uuid-retry");
        delivery.setEndpointId(owner.getId());
        delivery.setEventType("prworkflow.started");
        delivery.setPayloadJson("{\"schemaVersion\":1}");
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setAttempts(5);
        delivery.setCreatedAt(java.time.Instant.now());
        deliveryRepository.save(delivery);

        // Even with a forged endpointId pointing at the other endpoint, the
        // redirect follows the delivery's own endpoint.
        mvc.perform(post("/admin/event-hooks/deliveries/{deliveryId}/retry", delivery.getId())
                        .with(csrf())
                        .param("endpointId", String.valueOf(other.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks/" + owner.getId() + "/deliveries"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void retryNonexistentDeliveryRedirectsToListWithError() throws Exception {
        mvc.perform(post("/admin/event-hooks/deliveries/{deliveryId}/retry", 99999L).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/event-hooks"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("error", "Delivery not found"));
    }

    private static EventHookEndpoint endpoint(String name, String url) {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setName(name);
        endpoint.setUrl(url);
        endpoint.setEventTypes("PR_WORKFLOW_STARTED");
        endpoint.setEnabled(true);
        return endpoint;
    }
}
