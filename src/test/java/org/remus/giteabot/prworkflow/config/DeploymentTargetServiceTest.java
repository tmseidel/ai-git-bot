package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTargetServiceTest {

    private DeploymentTargetRepository repository;
    private BotRepository botRepository;
    private EncryptionService encryptionService;
    private DeploymentTargetService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentTargetRepository.class);
        botRepository = mock(BotRepository.class);
        encryptionService = mock(EncryptionService.class);
        when(encryptionService.encrypt(any())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryptionService.decrypt(any())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            if (v != null && v.startsWith("ENC(") && v.endsWith(")")) {
                return v.substring(4, v.length() - 1);
            }
            return v;
        });
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DeploymentTargetService(repository, botRepository, encryptionService);
        entityManager = mock(jakarta.persistence.EntityManager.class);
        service.setEntityManager(entityManager);
    }

    private jakarta.persistence.EntityManager entityManager;

    private DeploymentTarget newTarget() {
        DeploymentTarget t = new DeploymentTarget();
        t.setName("ci");
        t.setStrategyType(DeploymentStrategyType.WEBHOOK);
        t.setConfigJson("{\"webhookUrl\":\"https://ci/x\"}");
        t.setTimeoutSeconds(120);
        return t;
    }

    @Test
    void saveEncryptsConfigJsonButReturnsCleartext() {
        DeploymentTarget saved = service.save(newTarget());

        // Returned object carries cleartext, not cipher.
        assertThat(saved.getConfigJson()).isEqualTo("{\"webhookUrl\":\"https://ci/x\"}");

        // The encryption service was invoked with the plaintext exactly once.
        verify(encryptionService).encrypt("{\"webhookUrl\":\"https://ci/x\"}");
    }

    @Test
    void saveRejectsBlankName() {
        DeploymentTarget t = newTarget();
        t.setName(" ");
        assertThatThrownBy(() -> service.save(t)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveRejectsNullStrategyType() {
        DeploymentTarget t = newTarget();
        t.setStrategyType(null);
        assertThatThrownBy(() -> service.save(t)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveRejectsDuplicateNameOnInsert() {
        when(repository.existsByName("ci")).thenReturn(true);
        assertThatThrownBy(() -> service.save(newTarget()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(repository, never()).save(any());
    }

    @Test
    void saveAllowsRenameThatStillUnique() {
        DeploymentTarget t = newTarget();
        t.setId(7L);
        when(repository.existsByNameAndIdNot("ci", 7L)).thenReturn(false);
        DeploymentTarget saved = service.save(t);
        assertThat(saved.getId()).isEqualTo(7L);
    }

    @Test
    void saveFillsDefaults() {
        DeploymentTarget t = newTarget();
        t.setConfigJson(null);
        t.setTimeoutSeconds(0);
        DeploymentTarget saved = service.save(t);
        // We can't read the saved entity's configJson directly (it's encrypted on the captor),
        // but the returned value contains cleartext defaults.
        assertThat(saved.getTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void findAllDecryptsAll() {
        DeploymentTarget enc = newTarget();
        enc.setConfigJson("ENC({\"k\":\"v\"})");
        when(repository.findAll()).thenReturn(List.of(enc));
        List<DeploymentTarget> all = service.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getConfigJson()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void findByIdDecrypts() {
        DeploymentTarget enc = newTarget();
        enc.setConfigJson("ENC({\"k\":\"v\"})");
        when(repository.findById(1L)).thenReturn(Optional.of(enc));
        DeploymentTarget loaded = service.findById(1L).orElseThrow();
        assertThat(loaded.getConfigJson()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void deleteBlockedWhenBotReferencesTarget() {
        DeploymentTarget existing = newTarget();
        existing.setId(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        Bot bot = new Bot();
        bot.setDeploymentTarget(existing);
        when(botRepository.findAll()).thenReturn(List.of(bot));

        assertThatThrownBy(() -> service.deleteById(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still used");
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteUnknownIdThrows() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteById(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteSucceedsWhenUnused() {
        DeploymentTarget existing = newTarget();
        existing.setId(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(botRepository.findAll()).thenReturn(List.of());
        service.deleteById(5L);
        verify(repository).deleteById(5L);
    }

    @Test
    void availableStrategyTypesIsM3Subset() {
        assertThat(service.availableStrategyTypes())
                .containsExactly(DeploymentStrategyType.WEBHOOK, DeploymentStrategyType.STATIC);
    }

    @Test
    void findByIdDetachesEntityBeforeDecrypting() {
        DeploymentTarget enc = newTarget();
        enc.setId(1L);
        enc.setConfigJson("ENC({\"k\":\"v\"})");
        when(repository.findById(1L)).thenReturn(Optional.of(enc));
        service.findById(1L).orElseThrow();
        // Must detach BEFORE we mutate configJson, so a later flush in the
        // same persistence context cannot write the plaintext back.
        verify(entityManager).detach(enc);
    }

    @Test
    void findAllDetachesEveryEntityBeforeDecrypting() {
        DeploymentTarget enc1 = newTarget();
        enc1.setId(1L);
        enc1.setConfigJson("ENC({\"k\":\"v\"})");
        DeploymentTarget enc2 = newTarget();
        enc2.setId(2L);
        enc2.setName("ci2");
        enc2.setConfigJson("ENC({\"k\":\"v2\"})");
        when(repository.findAll()).thenReturn(java.util.List.of(enc1, enc2));
        service.findAll();
        verify(entityManager).detach(enc1);
        verify(entityManager).detach(enc2);
    }

    @Test
    void saveDetachesReturnedEntityBeforeExposingPlaintext() {
        DeploymentTarget t = newTarget();
        DeploymentTarget saved = service.save(t);
        // Whatever the repository returned must have been detached before the
        // service replaced its (cipher) configJson with the cleartext.
        verify(entityManager).detach(saved);
    }
}


