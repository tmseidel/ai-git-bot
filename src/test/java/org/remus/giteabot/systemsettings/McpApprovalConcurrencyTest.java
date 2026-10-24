package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.persistence.EntityManager;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Independent committed transactions; only remote discovery is mocked. No timing sleeps. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpApprovalConcurrencyTest {
    private static final String NAME = "mcp:docs:read";
    private static final String JSON = "{\"name\":\"docs\",\"url\":\"https://example.test/mcp\"}";
    private static final McpToolCatalog CATALOG = new McpToolCatalog(List.of(
            new McpToolDefinition("docs", "read", "Read", "Read documents", Map.of("type", "object"), NAME)));

    @Autowired private McpConfigurationService configurations;
    @Autowired private McpConfigurationRepository configurationRepository;
    @Autowired private McpSelectedToolRepository selectedRepository;
    @Autowired private McpToolSelectionService selections;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private McpOrchestrationService mcpOrchestrationService;

    private Long id;

    @BeforeEach
    void setUp() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setName("approval-race-" + UUID.randomUUID());
        configuration.setJsonContent(JSON);
        id = configurations.save(configuration).getId();
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(CATALOG);
        selections.saveSelection(id, List.of(NAME), List.of(NAME), List.of(),
                selections.loadSelectionSnapshot(id).approvalCandidates());
    }

    @Test
    void ordinarySaveCannotUndoAnExplicitRevocationCommittedDuringDiscovery() throws Exception {
        var reviewed = selections.loadSelectionSnapshot(id).approvalCandidates();
        CountDownLatch discovering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> slow = pauseDiscovery(discovering, resume);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ordinary = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                slow.set(Thread.currentThread());
                // Retain both parent/child first-level state to exercise stale persistence contexts.
                configurationRepository.findById(id).orElseThrow().getSelectedTools().size();
                selectedRepository.findByMcpConfigurationId(id);
                selections.saveSelection(id, List.of(NAME));
            }));
            try {
                await(discovering);
                workers.submit(() -> selections.saveSelection(id, List.of(NAME), List.of(), List.of(NAME), reviewed))
                        .get(15, TimeUnit.SECONDS);
                assertFalse(selectedRepository.findByMcpConfigurationId(id).getFirst().isReviewReadOnlyApproved());
            } finally {
                resume.countDown();
            }
            ordinary.get(15, TimeUnit.SECONDS);
        }
        assertFalse(selectedRepository.findByMcpConfigurationId(id).getFirst().isReviewReadOnlyApproved());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configurationChangeDuringDiscoveryRejectsTheOldCatalogWithoutHoldingTheRowLock(boolean snapshot) throws Exception {
        CountDownLatch discovering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> slow = pauseDiscovery(discovering, resume);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ordinary = workers.submit(() -> {
                slow.set(Thread.currentThread());
                if (snapshot) {
                    selections.loadSelectionSnapshot(id);
                } else {
                    selections.saveSelection(id, List.of(NAME));
                }
            });
            try {
                await(discovering);
                workers.submit(() -> {
                    McpConfiguration edit = new McpConfiguration();
                    edit.setId(id);
                    edit.setName(configurations.findById(id).orElseThrow().getName());
                    edit.setJsonContent(JSON.replace("example.test", "changed.test"));
                    configurations.save(edit);
                }).get(15, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> ordinary.get(15, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertFalse(selectedRepository.findByMcpConfigurationId(id).getFirst().isReviewReadOnlyApproved());
    }

    @Test
    void ordinarySaveRefreshesCachedFlagsAfterValidationInvalidatesTheSameRow() throws Exception {
        CountDownLatch discovering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> slow = pauseDiscovery(discovering, resume);
        Long selectedId = selectedRepository.findByMcpConfigurationId(id).getFirst().getId();
        McpToolCatalog changed = new McpToolCatalog(List.of(new McpToolDefinition(
                "docs", "read", "Read", "Read documents", Map.of("type", "array"), NAME)));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ordinary = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                slow.set(Thread.currentThread());
                selectedRepository.findByMcpConfigurationId(id);
                selections.saveSelection(id, List.of(NAME));
            }));
            try {
                await(discovering);
                workers.submit(() -> selections.readReviewReadOnlyApprovals(
                        configurations.findById(id).orElseThrow(), changed)).get(15, TimeUnit.SECONDS);
                McpSelectedTool invalidated = selectedRepository.findByMcpConfigurationId(id).getFirst();
                assertEquals(selectedId, invalidated.getId());
                assertFalse(invalidated.isReviewReadOnlyApproved());
            } finally {
                resume.countDown();
            }
            ordinary.get(15, TimeUnit.SECONDS);
        }
        assertFalse(selectedRepository.findByMcpConfigurationId(id).getFirst().isReviewReadOnlyApproved());
    }

    @Test
    void deletionCanCommitDuringDiscoveryAndTheSnapshotThenRejectsTheDeletedConfiguration() throws Exception {
        CountDownLatch discovering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> slow = pauseDiscovery(discovering, resume);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var snapshot = workers.submit(() -> {
                slow.set(Thread.currentThread());
                selections.loadSelectionSnapshot(id);
            });
            try {
                await(discovering);
                workers.submit(() -> configurations.deleteById(id)).get(15, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> snapshot.get(15, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertTrue(configurations.findById(id).isEmpty());
        assertTrue(selectedRepository.findByMcpConfigurationId(id).isEmpty());
    }

    @Test
    void publicReadRejectsACatalogCapturedForAnOlderConfigurationRevision() {
        McpConfiguration observed = configurations.findById(id).orElseThrow();
        McpConfiguration edit = configurations.decryptedView(observed);
        edit.setJsonContent(JSON.replace("example.test", "changed.test"));
        configurations.save(edit);
        assertThrows(IllegalArgumentException.class, () -> selections.readReviewReadOnlyApprovals(observed, CATALOG));
        assertFalse(selectedRepository.findByMcpConfigurationId(id).getFirst().isReviewReadOnlyApproved());
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt", "old-key"})
    @ExtendWith(OutputCaptureExtension.class)
    void validReplacementRepairsUnreadableStoredJsonAndRevokesApproval(String kind, CapturedOutput output) {
        McpConfiguration before = configurations.findById(id).orElseThrow();
        String replacement = JSON.replace("}", ",\"authorization_token\":\"repair-secret-sentinel\"}");
        String unreadable = "corrupt".equals(kind) ? "{unreadable-json-fixture"
                : "ENC:" + new EncryptionService("former-test-encryption-key").encrypt(replacement);
        jdbc.update("UPDATE mcp_configurations SET json_content = ? WHERE id = ?", unreadable, id);

        McpConfiguration edit = new McpConfiguration();
        edit.setId(id);
        edit.setName(before.getName());
        edit.setJsonContent(replacement);
        configurations.save(edit);

        McpConfiguration repaired = configurations.findById(id).orElseThrow();
        assertEquals(replacement, configurations.getDecryptedJsonContent(repaired));
        assertNotEquals(replacement, repaired.getJsonContent());
        assertNotEquals(before.getReviewApprovalRevision(), repaired.getReviewApprovalRevision());
        McpSelectedTool selected = selectedRepository.findByMcpConfigurationId(id).getFirst();
        assertFalse(selected.isReviewReadOnlyApproved());
        assertNull(selected.getReviewApprovalSchemaHash());
        assertNull(selected.getReviewApprovalConfigurationHash());
        assertTrue(selections.readReviewReadOnlyApprovals(repaired, CATALOG).isEmpty());
        assertFalse(output.getAll().contains(unreadable));
        assertFalse(output.getAll().contains("repair-secret-sentinel"));
    }

    private AtomicReference<Thread> pauseDiscovery(CountDownLatch discovering, CountDownLatch resume) {
        AtomicReference<Thread> slow = new AtomicReference<>();
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenAnswer(invocation -> {
            assertFalse(entityManager.contains(invocation.getArgument(0)), "discovery must use a detached configuration capture");
            if (Thread.currentThread() == slow.get()) {
                discovering.countDown();
                await(resume);
            }
            return CATALOG;
        });
        return slow;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "transaction barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
