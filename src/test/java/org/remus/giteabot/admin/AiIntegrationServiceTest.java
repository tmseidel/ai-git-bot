package org.remus.giteabot.admin;

import org.remus.giteabot.ai.AiProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.ollama.OllamaProviderMetadata;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiIntegrationServiceTest {

    @Mock
    private AiIntegrationRepository aiIntegrationRepository;

    @Mock
    private EncryptionService encryptionService;

    @Mock private AiProviderRegistry providerRegistry;
    @Mock private AiProviderMetadata provider;
    @Mock
    private AiIntegrationConcurrencyLimiter concurrencyLimiter;

    @InjectMocks
    private AiIntegrationService aiIntegrationService;

    @BeforeEach
    void providers() {
        lenient().when(providerRegistry.getProviderOrThrow(any())).thenReturn(provider);
    }

    @Test
    void save_encryptsApiKey() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("plain-api-key");
        when(encryptionService.encrypt("plain-api-key")).thenReturn("encrypted-value");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertEquals("encrypted-value", result.getApiKey());
        verify(encryptionService).encrypt("plain-api-key");
    }

    @Test
    void save_alwaysCallsEncrypt() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("any-api-key");
        when(encryptionService.encrypt("any-api-key")).thenReturn("encrypted-value");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertEquals("encrypted-value", result.getApiKey());
        verify(encryptionService).encrypt("any-api-key");
    }

    @Test
    void save_blankApiKeyOnUpdate_keepsStoredKeyWithoutReEncrypting() {
        AiIntegration integration = new AiIntegration();
        integration.setId(7L);
        integration.setApiKey("");
        AiIntegration existing = new AiIntegration();
        existing.setApiKey("stored-encrypted-key");
        when(aiIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertEquals("stored-encrypted-key", result.getApiKey());
        verifyNoInteractions(encryptionService);
        verify(provider).validateConfiguration(integration, null);
    }

    @Test
    void save_providerChangeCannotReuseAStoredKey() {
        AiIntegration existing = new AiIntegration();
        existing.setProviderType(AiProviderType.OPENAI);
        existing.setApiKey("stored-encrypted-key");
        when(aiIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        AiIntegration changed = new AiIntegration();
        changed.setId(7L);
        changed.setProviderType(AiProviderType.OPENROUTER);

        assertThrows(IllegalArgumentException.class, () -> aiIntegrationService.save(changed));

        verifyNoInteractions(encryptionService);
        verify(aiIntegrationRepository, never()).save(any());
    }

    @Test
    void save_switchToKeylessOllamaStillRequiresExplicitClear() {
        AiIntegration existing = new AiIntegration();
        existing.setProviderType(AiProviderType.OPENAI);
        existing.setApiKey("stored-encrypted-key");
        when(aiIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        AiIntegration changed = new AiIntegration();
        changed.setId(7L);
        changed.setProviderType(AiProviderType.OLLAMA);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ollamaService().save(changed));

        assertEquals("Enter a new API key or explicitly clear the stored key when changing providers", error.getMessage());
        verifyNoInteractions(encryptionService);
        verify(aiIntegrationRepository, never()).save(any());
    }

    @Test
    void save_switchToKeylessOllamaWithExplicitClearSavesWithoutACredential() {
        AiIntegration changed = new AiIntegration();
        changed.setId(7L);
        changed.setProviderType(AiProviderType.OLLAMA);
        changed.setApiUrl("http://localhost:11434");
        changed.setModel("local-model");
        changed.setApiKey("");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration saved = ollamaService().save(changed, true);

        assertEquals(AiProviderType.OLLAMA, saved.getProviderType());
        assertNull(saved.getApiKey());
        verifyNoInteractions(encryptionService);
    }

    @ParameterizedTest
    @NullSource
    // Only Ollama is registered in ollamaService(), so OpenRouter stands in for an unregistered provider.
    @EnumSource(value = AiProviderType.class, names = "OPENROUTER")
    void save_requiresARegisteredProvider(AiProviderType providerType) {
        AiIntegration integration = new AiIntegration();
        integration.setProviderType(providerType);

        assertThrows(IllegalArgumentException.class, () -> ollamaService().save(integration));

        verifyNoInteractions(aiIntegrationRepository, encryptionService);
    }

    private AiIntegrationService ollamaService() {
        return new AiIntegrationService(aiIntegrationRepository, encryptionService,
                new AiProviderRegistry(List.of(new OllamaProviderMetadata(null))), concurrencyLimiter);
    }

    @Test
    void save_replacementKeyWinsOverClearAndDoesNotReadTheOldProviderKey() {
        AiIntegration integration = new AiIntegration();
        integration.setId(7L);
        integration.setProviderType(AiProviderType.OPENROUTER);
        integration.setApiKey("new-provider-key");
        when(encryptionService.encrypt("new-provider-key")).thenReturn("new-ciphertext");
        when(aiIntegrationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("new-ciphertext", aiIntegrationService.save(integration, true).getApiKey());

        verify(provider).validateConfiguration(integration, "new-provider-key");
        verify(aiIntegrationRepository, never()).findById(anyLong());
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void save_clearApiKey_removesStoredKey() {
        AiIntegration integration = new AiIntegration();
        integration.setId(7L);
        integration.setApiKey("");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration, true);

        assertNull(result.getApiKey());
        verify(aiIntegrationRepository, never()).findById(anyLong());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_nullApiKey_staysNull() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey(null);
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertNull(result.getApiKey());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void decryptApiKey_callsDecrypt() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("encrypted-value");
        when(encryptionService.decrypt("encrypted-value")).thenReturn("plain-api-key");

        String result = aiIntegrationService.decryptApiKey(integration);

        assertEquals("plain-api-key", result);
        verify(encryptionService).decrypt("encrypted-value");
    }

    @Test
    void decryptApiKey_nullKey_returnsNull() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey(null);

        String result = aiIntegrationService.decryptApiKey(integration);

        assertNull(result);
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void deleteById_delegatesToRepository() {
        aiIntegrationService.deleteById(1L);

        verify(concurrencyLimiter).forget(1L);

        verify(aiIntegrationRepository).deleteById(1L);
    }
}
