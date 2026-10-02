package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AiIntegrationService {

    private final AiIntegrationRepository aiIntegrationRepository;
    private final EncryptionService encryptionService;
    private final AiProviderRegistry providerRegistry;

    @Transactional(readOnly = true)
    public List<AiIntegration> findAll() {
        return aiIntegrationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<AiIntegration> findById(Long id) {
        return aiIntegrationRepository.findById(id);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiIntegration save(AiIntegration integration) {
        return save(integration, false);
    }

    /**
     * Saves an AI integration, resolving the API key from the form input.
     *
     * <p>The key field is a one-way write: the stored value is never echoed
     * back into the form. A blank field therefore means "keep the stored
     * value" for the same provider, while {@code clearApiKey} requests explicit removal (the Clear
     * button in the UI). Re-encrypting the kept ciphertext would corrupt the
     * key, so only freshly provided plaintext keys are encrypted.</p>
     */
    // Repository methods own their transactions; provider HTTP validation must not hold a database connection.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiIntegration save(AiIntegration integration, boolean clearApiKey) {
        String apiKey = integration.getApiKey();
        boolean newKey = apiKey != null && !apiKey.isBlank();
        String retainedCiphertext = null;
        if (!newKey && !clearApiKey && integration.getId() != null) {
            AiIntegration existing = aiIntegrationRepository.findById(integration.getId())
                    .orElseThrow(() -> new IllegalArgumentException("AI integration not found"));
            if (!Objects.equals(existing.getProviderType(), integration.getProviderType())) {
                throw new IllegalArgumentException("Enter a new API key or explicitly clear the stored key when changing providers");
            }
            retainedCiphertext = existing.getApiKey();
        }
        // Keep ciphertext available to provider validation without decrypting it on an ordinary edit.
        integration.setApiKey(newKey ? apiKey : retainedCiphertext);
        providerRegistry.getProviderOrThrow(integration.getProviderType())
                .validateConfiguration(integration, newKey ? apiKey : null);
        integration.setApiKey(newKey ? encryptionService.encrypt(apiKey) : retainedCiphertext);
        return aiIntegrationRepository.save(integration);
    }

    public void deleteById(Long id) {
        aiIntegrationRepository.deleteById(id);
    }

    public String decryptApiKey(AiIntegration integration) {
        String apiKey = integration.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(apiKey);
    }
}
