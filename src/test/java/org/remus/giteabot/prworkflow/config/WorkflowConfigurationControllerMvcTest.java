package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full Spring MVC stack and posts a workflow-selection form against
 * {@link WorkflowConfigurationController#saveWorkflowSelection}. Reproduces
 * the operator-reported "values are not applied" path: pick a real registered
 * workflow with a non-empty params schema, submit two distinct sets of values
 * via {@code params.<key>__<field>} form fields and assert that each POST
 * round-trips into {@code workflow_selection_params} as expected.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowConfigurationControllerMvcTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private WorkflowConfigurationRepository configurationRepository;
    @Autowired private WorkflowSelectionRepository selectionRepository;
    @Autowired private PrWorkflowRegistry registry;
    @Autowired private TransactionTemplate tx;

    @Test
    @WithMockUser(roles = "ADMIN")
    void postFormWithParams_persistsAndOverwritesOnRepeat() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();

        PrWorkflow wf = registry.all().stream()
                .filter(w -> !w.paramsSchema().fields().isEmpty())
                .findFirst()
                .orElseThrow();
        String key = wf.key();

        Long cfgId = tx.execute(s -> {
            WorkflowConfiguration cfg = new WorkflowConfiguration();
            cfg.setName("Mvc-" + System.nanoTime());
            cfg.setDefaultEntry(false);
            cfg.setCreatedAt(Instant.now());
            cfg.setUpdatedAt(Instant.now());
            return configurationRepository.save(cfg).getId();
        });
        assertNotNull(cfgId);

        Map<String, String> first = new LinkedHashMap<>();
        for (var f : wf.paramsSchema().fields()) {
            first.put(f.name(), sample(f, "alpha"));
        }
        post1(mvc, cfgId, key, first);

        Map<String, String> firstStored = tx.execute(s ->
                selectionRepository.findByConfigurationIdAndWorkflowKey(cfgId, key)
                        .orElseThrow().getParamsMap());
        for (var e : first.entrySet()) {
            assertEquals(e.getValue(), firstStored.get(e.getKey()),
                    "first POST did not persist " + e.getKey());
        }

        Map<String, String> second = new LinkedHashMap<>();
        for (var f : wf.paramsSchema().fields()) {
            second.put(f.name(), sample(f, "bravo"));
        }
        post1(mvc, cfgId, key, second);

        Map<String, String> secondStored = tx.execute(s ->
                selectionRepository.findByConfigurationIdAndWorkflowKey(cfgId, key)
                        .orElseThrow().getParamsMap());
        for (var e : second.entrySet()) {
            assertEquals(e.getValue(), secondStored.get(e.getKey()),
                    "second POST did not overwrite " + e.getKey()
                            + " (still: " + secondStored.get(e.getKey()) + ")");
        }

        // Regression guard against the Thymeleaf preprocessing-`__...__` bug
        // that mangled `'params.' + ${row.workflowKey} + '__' + ${field.name}`
        // into a literal `params + ${row.workflowKey} + framework` field name,
        // which caused the controller to receive an empty workflowParams map
        // and silently keep the old values. The fix uses literal-substitution
        // `|params.${row.workflowKey}__${field.name}|`. Verify the rendered
        // workflows page exposes the correctly-named input for every schema
        // field AND the most recently persisted values.
        String html = mvc.perform(get(
                "/system-settings/workflow-configurations/{id}/workflows", cfgId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var f : wf.paramsSchema().fields()) {
            String expectedName = "params." + key + "." + f.name();
            assertTrue(html.contains("name=\"" + expectedName + "\""),
                    "rendered form does not contain a correctly-named input for "
                            + f.name() + " (expected name=\"" + expectedName + "\")"
                            + "\nHTML snippet around 'params':\n"
                            + extractAround(html, "params", 250));
        }
        for (var e : second.entrySet()) {
            assertTrue(html.contains(e.getValue()),
                    "GET form does not contain updated value '" + e.getValue()
                            + "' for field " + e.getKey());
        }
    }

    private void post1(MockMvc mvc, Long cfgId, String key,
                       Map<String, String> values) throws Exception {
        var req = post("/system-settings/workflow-configurations/{id}/workflows/save", cfgId)
                .with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .param("selectedWorkflowKeys", key);
        for (var e : values.entrySet()) {
            req.param("params." + key + "." + e.getKey(), e.getValue());
        }
        mvc.perform(req).andExpect(status().is3xxRedirection());
    }

    private static String sample(org.remus.giteabot.prworkflow.WorkflowParamField field, String tag) {
        return switch (field.type()) {
            case STRING, TEXT, SECRET -> tag + "-" + field.name();
            case BOOLEAN -> Boolean.toString(tag.equals("alpha"));
            case INTEGER -> tag.equals("alpha") ? "1" : "99";
            case ENUM -> field.allowedValues().isEmpty() ? tag : field.allowedValues().get(0).key();
        };
    }

    private static String extractAround(String html, String needle, int radius) {
        int idx = html.indexOf(needle);
        if (idx < 0) return "(needle '" + needle + "' not found in HTML)";
        int from = Math.max(0, idx - radius);
        int to = Math.min(html.length(), idx + radius);
        return html.substring(from, to);
    }
}

