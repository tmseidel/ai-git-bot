package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real selection/configuration persistence and authenticated forms; only the external catalog is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpReviewReadOnlyApprovalMvcTest {
    private static final String NAME = "mcp:docs:read";
    private static final String OTHER = "mcp:docs:other";
    private static final String JSON = """
            {"name":"docs","url":"https://example.test/mcp","headers":{"X-Key":"credential-sentinel"}}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private McpConfigurationService configurations;
    @Autowired private McpToolSelectionService selections;
    @Autowired private McpSelectedToolRepository repository;
    @MockitoBean private McpOrchestrationService mcpOrchestrationService;

    private Long id;
    private McpToolCatalog catalog;

    @BeforeEach
    void setUp() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setName("Approval fixture");
        configuration.setJsonContent(JSON);
        id = configurations.save(configuration).getId();
        catalog = new McpToolCatalog(List.of(tool("read", Map.of("type", "object")),
                tool("other", Map.of("type", "object"))));
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(catalog);
    }

    @Test
    void selectionAndClientProofFieldsNeverGrantApprovalAndLegacySelectionStaysUnchanged() throws Exception {
        mvc.perform(post(saveUrl()).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("selectedQualifiedNames", NAME, OTHER)
                        .param("reviewReadOnlyApproved", "true")
                        .param("reviewApprovalSchemaHash", "forged")
                        .param("reviewApprovalConfigurationHash", "forged"))
                .andExpect(redirectedUrl("/system-settings"));
        assertEquals(2, repository.findByMcpConfigurationId(id).size());
        assertUnapproved();
        assertEquals(catalog, selections.filterCatalogForPrompt(configurations.findById(id).orElseThrow(), catalog));
        mvc.perform(get("/bots/mcp-configurations/" + id + "/selected-tools").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void configurationFormCannotMassAssignSelectionOrApprovalProofs() throws Exception {
        mvc.perform(post("/system-settings/mcp-configurations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("name", "Forged nested selections").param("jsonContent", JSON)
                        .param("selectedTools[0].mcpConfiguration.id", id.toString())
                        .param("selectedTools[0].qualifiedName", NAME).param("selectedTools[0].serverName", "docs")
                        .param("selectedTools[0].toolName", "read").param("selectedTools[0].reviewReadOnlyApproved", "true")
                        .param("selectedTools[0].reviewApprovalSchemaHash", "forged")
                        .param("selectedTools[0].reviewApprovalConfigurationHash", "forged"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        assertTrue(repository.findByMcpConfigurationId(id).isEmpty());
        Long created = configurations.findAll().stream().filter(c -> "Forged nested selections".equals(c.getName()))
                .findFirst().orElseThrow().getId();
        assertTrue(repository.findByMcpConfigurationId(created).isEmpty());
    }

    @Test
    void explicitAdminApprovalPersistsServerEvidenceAndCanBeRevoked() throws Exception {
        MvcResult form = form();
        String html = form.getResponse().getContentAsString();
        assertTrue(html.contains("Read-only approval (new review protocol)"));
        assertTrue(html.contains("LEGACY0 routes do not enforce this approval"));
        assertFalse(html.contains("credential-sentinel"));
        assertFalse(html.contains("reviewApprovalConfigurationHash"));
        mvc.perform(post(saveUrl()).session(session(form)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(form)).param("selectedQualifiedNames", NAME, OTHER)
                        .param("reviewReadOnlyApprovedQualifiedNames", NAME)
                        .param("reviewApprovalSchemaHash", "forged").param("reviewApprovalConfigurationHash", "forged"))
                .andExpect(redirectedUrl("/system-settings"));
        var evidence = readApprovals(catalog);
        assertEquals(1, evidence.size());
        assertEquals(id, evidence.getFirst().mcpConfigurationId());
        assertEquals(NAME, evidence.getFirst().qualifiedName());
        assertTrue(evidence.getFirst().schemaHash().matches("[a-f0-9]{64}"));
        assertTrue(evidence.getFirst().configurationHash().matches("[a-f0-9]{64}"));
        var row = repository.findByMcpConfigurationId(id).stream().filter(t -> NAME.equals(t.getQualifiedName())).findFirst().orElseThrow();
        assertEquals(evidence.getFirst().schemaHash(), row.getReviewApprovalSchemaHash());
        selections.saveSelection(id, List.of(NAME, OTHER));
        assertEquals(evidence, readApprovals(catalog));
        assertNotEquals(row.getId(), repository.findByMcpConfigurationId(id).getFirst().getId());
        assertTrue(selections.loadAvailableTools(id).stream().anyMatch(t -> NAME.equals(t.qualifiedName()) && t.reviewReadOnlyApproved()));

        MvcResult revoke = form();
        mvc.perform(post(saveUrl()).session(session(revoke)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(revoke)).param("selectedQualifiedNames", NAME, OTHER)
                        .param("reviewReadOnlyRevokedQualifiedNames", NAME))
                .andExpect(redirectedUrl("/system-settings"));
        assertUnapproved();
    }

    @ParameterizedTest
    @ValueSource(strings = {OTHER, "mcp:docs:forged", "mcp:other:read"})
    void forgedOrUnselectedApprovalRejectsTheWholeSave(String requested) throws Exception {
        selections.saveSelection(id, List.of(NAME));
        MvcResult form = form();
        mvc.perform(post(saveUrl()).session(session(form)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(form)).param("selectedQualifiedNames", NAME)
                        .param("reviewReadOnlyApprovedQualifiedNames", requested))
                .andExpect(redirectedUrl(toolsUrl())).andExpect(flash().attribute("error", containsString("selected, available, unchanged")));
        assertEquals(List.of(NAME), selections.loadSelectedTools(id).stream().map(McpToolSelectionRow::qualifiedName).toList());
        assertUnapproved();
    }

    @Test
    void unavailableApprovalAndStaleSchemaFormCannotRebindTrust() throws Exception {
        approve();
        MvcResult form = form();
        catalog = new McpToolCatalog(List.of(tool("read", Map.of("type", "object", "required", List.of("path")))));
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(catalog);
        mvc.perform(post(saveUrl()).session(session(form)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(form)).param("selectedQualifiedNames", NAME)
                        .param("reviewReadOnlyApprovedQualifiedNames", NAME)
                        .param("reviewApprovalSchemaHash", selections.loadSelectionSnapshot(id).approvalCandidates().get(NAME).schemaHash()))
                .andExpect(redirectedUrl(toolsUrl())).andExpect(flash().attributeExists("error"));
        assertUnapproved();
    }

    @Test
    void schemaChangesAndUnavailabilityInvalidatePersistedApprovalWithoutResurrection() {
        approve();
        McpToolCatalog changed = new McpToolCatalog(List.of(tool("read", Map.of("type", "array"))));
        assertTrue(readApprovals(changed).isEmpty());
        assertUnapproved();
        approve();
        assertTrue(readApprovals(McpToolCatalog.empty()).isEmpty());
        assertUnapproved();
    }

    @Test
    void unavailableToolCannotBeExplicitlyApprovedEvenWhenAlreadySelected() {
        selections.saveSelection(id, List.of(NAME));
        var shown = selections.loadSelectionSnapshot(id).approvalCandidates();
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(McpToolCatalog.empty());
        assertThrows(IllegalArgumentException.class, () -> selections.saveSelection(id, List.of(NAME), List.of(NAME), List.of(), shown));
        assertUnapproved();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"docs\",\"url\":\"https://other.test/mcp\",\"headers\":{\"X-Key\":\"credential-sentinel\"}}",
            "{\"name\":\"docs\",\"url\":\"https://example.test/mcp\",\"headers\":{\"X-Key\":\"rotated\"}}",
            "{\"name\":\"docs\",\"url\":\"https://example.test/mcp\",\"headers\":{\"X-Key\":\"${env:OTHER_KEY}\"}}",
            "{\"name\":\"renamed\",\"url\":\"https://example.test/mcp\",\"headers\":{\"X-Key\":\"credential-sentinel\"}}"
    })
    void actualConfigurationChangesInvalidateApprovalAndRevertingDoesNotRestoreIt(String json) {
        approve();
        saveConfiguration(json);
        assertUnapproved();
        saveConfiguration(JSON);
        assertUnapproved();
        assertEquals(1, selections.loadSelectedTools(id).size());
    }

    @Test
    void unchangedConfigurationAndReorderedJsonKeepApprovalAndSelection() {
        approve();
        var approved = readApprovals(catalog);
        saveConfiguration("{\"headers\":{\"X-Key\":\"credential-sentinel\"}, \"url\":\"https://example.test/mcp\",\"name\":\"docs\"}");
        assertEquals(approved, readApprovals(catalog));
        assertEquals(1, selections.loadSelectedTools(id).size());
    }

    @Test
    void changedConfigurationRejectsAnOldFormAndFreshExplicitApprovalWorks() throws Exception {
        approve();
        MvcResult form = form();
        saveConfiguration(JSON.replace("example.test", "other.test"));
        mvc.perform(post(saveUrl()).session(session(form)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(form)).param("selectedQualifiedNames", NAME)
                        .param("reviewReadOnlyApprovedQualifiedNames", NAME))
                .andExpect(redirectedUrl(toolsUrl())).andExpect(flash().attributeExists("error"));
        assertUnapproved();
        approve();
        assertEquals(1, readApprovals(catalog).size());
    }

    @Test
    void configurationEvidenceIsOpaquePerConfigurationAndCannotBeReplayedAfterAChangeAndRevert() {
        McpConfiguration first = configurations.findById(id).orElseThrow();
        String original = configurations.configurationHash(first);
        McpConfiguration identical = new McpConfiguration();
        identical.setName("Identical JSON with independent identity");
        identical.setJsonContent(JSON);
        identical = configurations.save(identical);
        assertNotEquals(original, configurations.configurationHash(identical));
        assertTrue(original.matches("[a-f0-9]{64}"));
        saveConfiguration(JSON.replace("credential-sentinel", "changed-secret"));
        String changed = configurations.configurationHash(configurations.findById(id).orElseThrow());
        assertNotEquals(original, changed);
        saveConfiguration(JSON);
        assertNotEquals(original, configurations.configurationHash(configurations.findById(id).orElseThrow()));
    }

    @Test
    void configurationFormCannotReplaceTheServerOwnedApprovalRevision() throws Exception {
        String original = configurations.configurationHash(configurations.findById(id).orElseThrow());
        mvc.perform(post("/system-settings/mcp-configurations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("id", id.toString()).param("name", "Name-only edit").param("jsonContent", JSON)
                        .param("reviewApprovalRevision", "client-owned-revision"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        assertEquals(original, configurations.configurationHash(configurations.findById(id).orElseThrow()));
    }

    @Test
    void reorderedSchemaKeysPreserveApprovalButChangedMetadataInvalidatesIt() {
        var schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of("path", Map.of("type", "string")));
        catalog = new McpToolCatalog(List.of(tool("read", schema)));
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(catalog);
        approve();
        var approved = readApprovals(catalog);
        var reordered = new java.util.LinkedHashMap<String, Object>();
        reordered.put("properties", schema.get("properties"));
        reordered.put("type", "object");
        catalog = new McpToolCatalog(List.of(tool("read", reordered)));
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(catalog);
        selections.saveSelection(id, List.of(NAME));
        assertEquals(approved, readApprovals(catalog));
        catalog = new McpToolCatalog(List.of(new McpToolDefinition("docs", "read", "Changed tool", "Changed description", reordered, NAME)));
        assertUnapproved();
    }

    @Test
    void removingAndReselectingAToolDoesNotRecreateApproval() {
        approve();
        selections.saveSelection(id, List.of());
        selections.saveSelection(id, List.of(NAME));
        assertUnapproved();
    }

    @Test
    void unchangedCheckedOldFormCannotResurrectApprovalAfterConfigurationChangesAndReverts() throws Exception {
        approve();
        MvcResult form = form();
        saveConfiguration(JSON.replace("example.test", "other.test"));
        saveConfiguration(JSON);
        mvc.perform(post(saveUrl()).session(session(form)).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("approvalFormToken", token(form)).param("selectedQualifiedNames", NAME))
                .andExpect(redirectedUrl("/system-settings"));
        assertUnapproved();
    }

    @Test
    void anUnboundPersistedBooleanIsNotApprovalEvidence() {
        selections.saveSelection(id, List.of(NAME));
        McpSelectedTool selected = repository.findByMcpConfigurationId(id).getFirst();
        selected.setReviewReadOnlyApproved(true);
        selected.setReviewApprovalSchemaHash("forged");
        selected.setReviewApprovalConfigurationHash("forged");
        repository.flush();
        assertUnapproved();
    }

    @Test
    void missingOrForgedSessionProofCannotGrantApproval() throws Exception {
        mvc.perform(post(saveUrl()).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("selectedQualifiedNames", NAME).param("reviewReadOnlyApprovedQualifiedNames", NAME)
                        .param("approvalFormToken", "forged").param("reviewApprovalSchemaHash", "forged"))
                .andExpect(redirectedUrl(toolsUrl())).andExpect(flash().attributeExists("error"));
        assertTrue(repository.findByMcpConfigurationId(id).isEmpty());
    }

    @Test
    void authenticationAdminRoleAndCsrfAreRequired() throws Exception {
        mvc.perform(post(saveUrl()).with(user("admin").roles("ADMIN")).param("selectedQualifiedNames", NAME))
                .andExpect(status().isForbidden());
        mvc.perform(post(saveUrl()).with(user("reader").roles("USER")).with(csrf()).param("selectedQualifiedNames", NAME))
                .andExpect(status().isForbidden());
        mvc.perform(get(toolsUrl()).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post(saveUrl()).with(csrf()).param("selectedQualifiedNames", NAME)).andExpect(status().is3xxRedirection());
        assertTrue(repository.findByMcpConfigurationId(id).isEmpty());
    }

    private void approve() {
        selections.saveSelection(id, List.of(NAME), List.of(NAME), List.of(), selections.loadSelectionSnapshot(id).approvalCandidates());
    }

    private void assertUnapproved() {
        assertTrue(readApprovals(catalog).isEmpty());
        repository.findByMcpConfigurationId(id).forEach(tool -> {
            assertFalse(tool.isReviewReadOnlyApproved());
            assertNull(tool.getReviewApprovalSchemaHash());
            assertNull(tool.getReviewApprovalConfigurationHash());
        });
    }

    private void saveConfiguration(String json) {
        McpConfiguration edit = new McpConfiguration();
        edit.setId(id);
        edit.setName("Approval fixture");
        edit.setJsonContent(json);
        configurations.save(edit);
    }

    private List<McpToolSelectionService.ReviewReadOnlyApproval> readApprovals(McpToolCatalog discovered) {
        return selections.readReviewReadOnlyApprovals(configurations.findById(id).orElseThrow(), discovered);
    }

    private McpToolDefinition tool(String name, Map<String, Object> schema) {
        return new McpToolDefinition("docs", name, name, "Read-only hint is not admin approval", schema, "mcp:docs:" + name);
    }

    private String toolsUrl() { return "/system-settings/mcp-configurations/" + id + "/tools"; }
    private String saveUrl() { return toolsUrl() + "/save"; }
    private MvcResult form() throws Exception {
        return mvc.perform(get(toolsUrl()).with(user("admin").roles("ADMIN")).locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn();
    }
    private MockHttpSession session(MvcResult form) { return (MockHttpSession) form.getRequest().getSession(); }
    private String token(MvcResult form) { return (String) form.getModelAndView().getModel().get("approvalFormToken"); }
}
