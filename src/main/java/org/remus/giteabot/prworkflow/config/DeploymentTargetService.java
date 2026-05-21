package org.remus.giteabot.prworkflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;
import org.remus.giteabot.prworkflow.deployment.mcp.McpDeploymentConfig;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationRepository;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD for {@link DeploymentTarget} rows. Encrypts the {@code configJson}
 * column on save via {@link EncryptionService} so the cipher text is what
 * Hibernate persists; callers receive cleartext through {@link #findById}
 * and {@link #findAll}.
 *
 * <p>Read methods <strong>detach</strong> the loaded entities from the JPA
 * persistence context before decrypting the {@code configJson} field. This
 * guarantees the plaintext can never be flushed back to the database by a
 * later transaction in the same context — the "encrypted at rest"
 * invariant only holds because nothing mutates a managed entity in-place
 * with the cleartext value.</p>
 *
 * <p>Guards:</p>
 * <ul>
 *     <li>Name uniqueness (case-sensitive — DB UK).</li>
 *     <li>Cannot delete a target that is still referenced by a bot.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
public class DeploymentTargetService {

    private final DeploymentTargetRepository repository;
    private final BotRepository botRepository;
    private final EncryptionService encryptionService;
    private final McpConfigurationRepository mcpConfigurationRepository;
    private final McpToolSelectionService mcpToolSelectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    public DeploymentTargetService(DeploymentTargetRepository repository,
                                   BotRepository botRepository,
                                   EncryptionService encryptionService,
                                   McpConfigurationRepository mcpConfigurationRepository,
                                   McpToolSelectionService mcpToolSelectionService) {
        this.repository = repository;
        this.botRepository = botRepository;
        this.encryptionService = encryptionService;
        this.mcpConfigurationRepository = mcpConfigurationRepository;
        this.mcpToolSelectionService = mcpToolSelectionService;
    }

    /** Test seam: lets unit tests inject a mock EntityManager. */
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<DeploymentTarget> findAll() {
        List<DeploymentTarget> all = repository.findAll();
        all.forEach(this::detachAndDecrypt);
        return all;
    }

    @Transactional(readOnly = true)
    public Optional<DeploymentTarget> findById(Long id) {
        return repository.findById(id).map(target -> {
            detachAndDecrypt(target);
            return target;
        });
    }

    public DeploymentTarget save(DeploymentTarget target) {
        if (target.getName() == null || target.getName().isBlank()) {
            throw new IllegalArgumentException("Deployment target name must not be blank");
        }
        if (target.getStrategyType() == null) {
            throw new IllegalArgumentException("Deployment target strategy type must not be null");
        }
        if (target.getConfigJson() == null || target.getConfigJson().isBlank()) {
            // Allow empty {} to be persisted but never null — keeps NOT NULL constraint happy.
            target.setConfigJson("{}");
        }
        if (target.getTimeoutSeconds() <= 0) {
            target.setTimeoutSeconds(600);
        }
        boolean nameTaken = target.getId() == null
                ? repository.existsByName(target.getName())
                : repository.existsByNameAndIdNot(target.getName(), target.getId());
        if (nameTaken) {
            throw new IllegalArgumentException(
                    "A deployment target named '" + target.getName() + "' already exists");
        }
        if (target.getStrategyType() == DeploymentStrategyType.MCP) {
            validateMcpConfig(target.getConfigJson());
        }
        String plaintext = target.getConfigJson();
        target.setConfigJson(encryptionService.encrypt(plaintext));
        DeploymentTarget saved = repository.save(target);
        // Force the pending INSERT/UPDATE to be sent to the DB *before* we
        // detach the entity below. Without this flush, an UPDATE issued via
        // EntityManager#merge (the path Spring Data takes when the entity
        // has a non-null id) is still only queued in the persistence
        // context — detaching the entity would discard those pending
        // changes and the row would never be updated, even though the
        // transaction commits cleanly. INSERTs happen to work because
        // GenerationType.IDENTITY forces an immediate INSERT at persist
        // time to obtain the generated id, but UPDATEs need this explicit
        // flush to survive the detach below.
        if (entityManager != null) {
            entityManager.flush();
            // Detach before exposing cleartext to the caller so callers
            // cannot accidentally re-flush the plaintext value back to the
            // database.
            entityManager.detach(saved);
        }
        saved.setConfigJson(plaintext);
        return saved;
    }

    public void deleteById(Long id) {
        DeploymentTarget target = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown deployment target id=" + id));
        long inUse = botRepository.findAll().stream()
                .filter(bot -> bot.getDeploymentTarget() != null
                        && bot.getDeploymentTarget().getId().equals(target.getId()))
                .count();
        if (inUse > 0) {
            throw new IllegalStateException(
                    "Cannot delete deployment target '" + target.getName() + "': still used by "
                            + inUse + " bot(s)");
        }
        repository.deleteById(id);
    }

    /**
     * Returns the set of strategy types the operator can pick from in the
     * admin UI. M3 shipped {@code WEBHOOK} and {@code STATIC}; M5 added
     * {@code MCP}; M6 adds {@code CI_ACTION}.
     */
    @Transactional(readOnly = true)
    public List<DeploymentStrategyType> availableStrategyTypes() {
        return List.of(
                DeploymentStrategyType.WEBHOOK,
                DeploymentStrategyType.STATIC,
                DeploymentStrategyType.MCP,
                DeploymentStrategyType.CI_ACTION);
    }

    /**
     * Validates an {@code MCP} deployment-target {@code configJson}
     * <em>before</em> encryption / persistence:
     * <ol>
     *     <li>parses the document via {@link McpDeploymentConfig#parse},</li>
     *     <li>resolves the referenced {@link McpConfiguration} (must exist),</li>
     *     <li>verifies that every referenced tool name
     *         ({@code deployTool} and, when set, {@code statusTool} /
     *         {@code teardownTool}) is part of the MCP configuration's
     *         whitelist managed by {@link McpToolSelectionService}.</li>
     * </ol>
     * Any failure throws {@link IllegalArgumentException} so the admin UI
     * surfaces an actionable error and the row is never saved.
     */
    private void validateMcpConfig(String configJson) {
        McpDeploymentConfig parsed;
        try {
            parsed = McpDeploymentConfig.parse(configJson, objectMapper);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid MCP deployment target config: " + e.getMessage(), e);
        }
        McpConfiguration mcpConfiguration = mcpConfigurationRepository.findById(parsed.mcpConfigurationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "MCP configuration id=" + parsed.mcpConfigurationId() + " not found"));
        Set<String> allowed = mcpToolSelectionService.selectedQualifiedToolNameSet(mcpConfiguration.getId());
        rejectIfNotWhitelisted(allowed, parsed.deployTool(), "deployTool");
        parsed.optionalStatusTool()
                .ifPresent(tool -> rejectIfNotWhitelisted(allowed, tool, "statusTool"));
        parsed.optionalTeardownTool()
                .ifPresent(tool -> rejectIfNotWhitelisted(allowed, tool, "teardownTool"));
    }

    private static void rejectIfNotWhitelisted(Set<String> allowed, String tool, String role) {
        if (!allowed.contains(tool)) {
            throw new IllegalArgumentException(
                    "MCP tool '" + tool + "' (" + role + ") is not whitelisted on the configured MCP server");
        }
    }

    /**
     * Detaches the entity from the persistence context, then replaces the
     * encrypted {@code configJson} with its plaintext value. The detach
     * step is what makes the in-place mutation safe — once detached, no
     * subsequent flush can write the plaintext back to the database.
     */
    private void detachAndDecrypt(DeploymentTarget target) {
        if (entityManager != null) {
            entityManager.detach(target);
        }
        if (target.getConfigJson() != null) {
            target.setConfigJson(encryptionService.decrypt(target.getConfigJson()));
        }
    }
}
