package org.remus.giteabot.systemsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class McpConfigurationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final McpConfigurationRepository mcpConfigurationRepository;
    private final BotRepository botRepository;
    private final EncryptionService encryptionService;
    private final McpSelectedToolRepository mcpSelectedToolRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<McpConfiguration> findAll() {
        return mcpConfigurationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<McpConfiguration> findById(Long id) {
        return mcpConfigurationRepository.findById(id);
    }

    /**
     * Validates and persists the configuration. The JSON content may contain
     * MCP server credentials (authorization tokens, custom headers), so it is
     * stored encrypted via {@link EncryptionService}. The incoming value is
     * always plaintext (e.g. from the admin form).
     */
    public McpConfiguration save(McpConfiguration mcpConfiguration) {
        if (mcpConfiguration.getName() == null || mcpConfiguration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        mcpConfiguration.setName(mcpConfiguration.getName().trim());
        if (mcpConfiguration.getJsonContent() == null || mcpConfiguration.getJsonContent().isBlank()) {
            throw new IllegalArgumentException("MCP JSON content is required");
        }
        validateJson(mcpConfiguration.getJsonContent());
        String name = mcpConfiguration.getName();
        String json = mcpConfiguration.getJsonContent();
        boolean duplicateName = mcpConfiguration.getId() == null
                ? mcpConfigurationRepository.existsByName(mcpConfiguration.getName())
                : mcpConfigurationRepository.existsByNameAndIdNot(mcpConfiguration.getName(), mcpConfiguration.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("An MCP configuration with this name already exists");
        }
        McpConfiguration stored = mcpConfiguration.getId() == null ? new McpConfiguration()
                : lockForApprovalUpdate(mcpConfiguration.getId());
        if (stored.getId() != null && !sameConfiguredJson(getDecryptedJsonContent(stored), json)) {
            stored.setReviewApprovalRevision(UUID.randomUUID().toString());
            refreshSelectedTools(stored.getId()).forEach(McpSelectedTool::clearReviewReadOnlyApproval);
        }
        // Never bind selections/approvals from the configuration form, or replace its orphan-removal collection.
        stored.setName(name);
        stored.setJsonContent(encryptionService.encrypt(json));
        // An existing row is already managed; merging its refreshed orphan-removal collection is unnecessary.
        return stored.getId() == null ? mcpConfigurationRepository.save(stored) : stored;
    }

    /** Identity binding to a server-owned opaque revision, never a fingerprint of credential-bearing JSON. */
    @Transactional(readOnly = true)
    public String configurationHash(McpConfiguration configuration) {
        if (configuration.getId() == null || configuration.getReviewApprovalRevision() == null) {
            throw new IllegalArgumentException("Cannot bind approval to an unsaved MCP configuration");
        }
        return approvalHash(Map.of("configurationId", configuration.getId(),
                "revision", configuration.getReviewApprovalRevision()));
    }

    McpConfiguration lockForApprovalUpdate(Long id) {
        McpConfiguration configuration = mcpConfigurationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        entityManager.refresh(configuration, LockModeType.PESSIMISTIC_WRITE);
        return configuration;
    }

    List<McpSelectedTool> refreshSelectedTools(Long id) {
        // The caller holds the parent row lock; cached approval flags may predate that lock.
        List<McpSelectedTool> selected = mcpSelectedToolRepository.findByMcpConfigurationId(id);
        selected.forEach(entityManager::refresh);
        return selected;
    }

    private boolean sameConfiguredJson(String stored, String incoming) {
        try {
            return OBJECT_MAPPER.readTree(stored).equals(OBJECT_MAPPER.readTree(incoming));
        } catch (Exception e) {
            // Unreadable previous content is a change; parser errors can quote credentials.
            return false;
        }
    }

    static String approvalHash(Object value) {
        try {
            Object jsonValue = OBJECT_MAPPER.convertValue(value, Object.class);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(OBJECT_MAPPER.writeValueAsBytes(jsonValue)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot hash MCP approval evidence");
        }
    }

    /** Decrypted JSON content of the configuration (may contain credentials). */
    @Transactional(readOnly = true)
    public String getDecryptedJsonContent(McpConfiguration configuration) {
        String json = configuration.getJsonContent();
        return (json == null || json.isBlank()) ? json : encryptionService.decrypt(json);
    }

    /**
     * Returns a transient copy of the configuration with decrypted JSON
     * content, for rendering in the admin edit form. Never persisted.
     */
    @Transactional(readOnly = true)
    public McpConfiguration decryptedView(McpConfiguration configuration) {
        McpConfiguration copy = new McpConfiguration();
        copy.setId(configuration.getId());
        copy.setName(configuration.getName());
        copy.setJsonContent(getDecryptedJsonContent(configuration));
        return copy;
    }

    public void deleteById(Long id) {
        McpConfiguration mcpConfiguration = mcpConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        List<Bot> bots = botRepository.findByMcpConfigurationId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("MCP configuration is used by bot(s): " + botNames);
        }
        mcpConfigurationRepository.delete(lockForApprovalUpdate(id));
    }

    void validateJson(String jsonContent) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP configuration must be valid JSON");
        }
        if (containsStdioTransport(root)) {
            throw new IllegalArgumentException("stdio MCP transport is not supported");
        }
        if (!containsRemoteEndpoint(root)) {
            throw new IllegalArgumentException("MCP configuration must contain a remote HTTP, HTTPS, WS, WSS, or SSE endpoint");
        }
    }

    private boolean containsStdioTransport(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (("transport".equalsIgnoreCase(field.getKey()) || "type".equalsIgnoreCase(field.getKey()))
                        && field.getValue().isTextual()
                        && "stdio".equalsIgnoreCase(field.getValue().asText())) {
                    return true;
                }
                if ("command".equalsIgnoreCase(field.getKey())) {
                    return true;
                }
                if (containsStdioTransport(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsStdioTransport(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsRemoteEndpoint(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText().toLowerCase();
            return value.startsWith("http://")
                    || value.startsWith("https://")
                    || value.startsWith("ws://")
                    || value.startsWith("wss://")
                    || value.startsWith("sse://");
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (containsRemoteEndpoint(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsRemoteEndpoint(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
