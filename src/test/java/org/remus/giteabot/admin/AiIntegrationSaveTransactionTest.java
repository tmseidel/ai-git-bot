package org.remus.giteabot.admin;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiIntegrationSaveTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothSaveOverloadsSuspendCallerTransactionDuringValidation(boolean explicitClearArgument) {
        var repository = mock(AiIntegrationRepository.class);
        var encryption = mock(EncryptionService.class);
        var registry = mock(AiProviderRegistry.class);
        var provider = mock(AiProviderMetadata.class);
        when(registry.getProviderOrThrow("openrouter")).thenReturn(provider);
        when(encryption.encrypt("new-key")).thenReturn("ciphertext");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            return null;
        }).when(provider).validateConfiguration(any(), any());

        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:save-transaction", "sa", "");
        var transactions = new DataSourceTransactionManager(dataSource);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
            context.registerBean(AiIntegrationService.class,
                    () -> new AiIntegrationService(repository, encryption, registry));
            context.refresh();
            var service = context.getBean(AiIntegrationService.class);
            var integration = new AiIntegration();
            integration.setProviderType("openrouter");
            integration.setApiKey("new-key");

            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var saved = explicitClearArgument ? service.save(integration, false) : service.save(integration);
                assertThat(saved.getApiKey()).isEqualTo("ciphertext");
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            });
            verify(provider).validateConfiguration(integration, "new-key");
            verify(repository).save(integration);
        }
    }
}
