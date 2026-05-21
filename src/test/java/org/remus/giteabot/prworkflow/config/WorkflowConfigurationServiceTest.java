package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowConfigurationServiceTest {

    @Mock private WorkflowConfigurationRepository configurationRepository;
    @Mock private WorkflowSelectionRepository selectionRepository;
    @Mock private BotRepository botRepository;

    @InjectMocks
    private WorkflowConfigurationService service;

    @Test
    void save_rejectsBlankName() {
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setName("   ");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.save(cfg));
        assertEquals("Name is required", e.getMessage());
        verify(configurationRepository, never()).save(any());
    }

    @Test
    void save_rejectsDuplicateName_forNewConfiguration() {
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setName("Security");
        when(configurationRepository.existsByName("Security")).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.save(cfg));
        assertEquals("A workflow configuration with this name already exists", e.getMessage());
    }

    @Test
    void save_newConfigurationCannotClaimDefaultFlag() {
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setName("Mine");
        cfg.setDefaultEntry(true);
        when(configurationRepository.existsByName("Mine")).thenReturn(false);
        when(configurationRepository.save(any(WorkflowConfiguration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowConfiguration saved = service.save(cfg);
        assertFalse(saved.isDefaultEntry());
    }

    @Test
    void save_defaultConfiguration_cannotBeRenamed() {
        WorkflowConfiguration persisted = new WorkflowConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);

        WorkflowConfiguration update = new WorkflowConfiguration();
        update.setId(1L);
        update.setName("Renamed");
        update.setDefaultEntry(true);

        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.save(update));
        assertEquals("The default workflow configuration cannot be renamed", e.getMessage());
    }

    @Test
    void save_defaultConfiguration_retainsDefaultFlagWhenClearedByCaller() {
        WorkflowConfiguration persisted = new WorkflowConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);
        WorkflowConfiguration update = new WorkflowConfiguration();
        update.setId(1L);
        update.setName("Default");
        update.setDefaultEntry(false);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));
        when(configurationRepository.existsByNameAndIdNot("Default", 1L)).thenReturn(false);
        when(configurationRepository.save(any(WorkflowConfiguration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowConfiguration saved = service.save(update);
        assertTrue(saved.isDefaultEntry());
    }

    @Test
    void deleteById_defaultConfiguration_throws() {
        WorkflowConfiguration persisted = new WorkflowConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.deleteById(1L));
        assertEquals("The default workflow configuration cannot be deleted", e.getMessage());
        verify(configurationRepository, never()).delete(any());
    }

    @Test
    void deleteById_configurationUsedByBots_throwsWithBotNames() {
        WorkflowConfiguration persisted = new WorkflowConfiguration();
        persisted.setId(7L);
        persisted.setName("Security");
        when(configurationRepository.findById(7L)).thenReturn(Optional.of(persisted));
        Bot bot = new Bot();
        bot.setName("SecurityBot");
        when(botRepository.findByWorkflowConfigurationId(7L)).thenReturn(List.of(bot));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.deleteById(7L));
        assertTrue(e.getMessage().contains("SecurityBot"));
    }

    @Test
    void deleteById_nonDefault_deletesSelectionsAndConfiguration() {
        WorkflowConfiguration persisted = new WorkflowConfiguration();
        persisted.setId(7L);
        persisted.setName("Security");
        when(configurationRepository.findById(7L)).thenReturn(Optional.of(persisted));
        when(botRepository.findByWorkflowConfigurationId(7L)).thenReturn(List.of());

        service.deleteById(7L);

        verify(selectionRepository).deleteByConfigurationId(7L);
        verify(configurationRepository).delete(persisted);
    }

    @Test
    void cloneConfiguration_copiesNameAndSelections() {
        WorkflowConfiguration source = new WorkflowConfiguration();
        source.setId(5L);
        source.setName("Security");
        WorkflowSelection sel = new WorkflowSelection();
        sel.setConfiguration(source);
        sel.setWorkflowKey("review");
        sel.replaceParams(java.util.Map.of("foo", "bar"));
        source.setSelectedWorkflows(List.of(sel));

        when(configurationRepository.findById(5L)).thenReturn(Optional.of(source));
        when(configurationRepository.existsByName("Copy of Security")).thenReturn(false);

        WorkflowConfiguration clone = service.cloneConfiguration(5L);

        assertNotSame(source, clone);
        assertEquals("Copy of Security", clone.getName());
        assertFalse(clone.isDefaultEntry());
        assertEquals(1, clone.getSelectedWorkflows().size());
        assertEquals("review", clone.getSelectedWorkflows().get(0).getWorkflowKey());
        assertEquals(java.util.Map.of("foo", "bar"),
                clone.getSelectedWorkflows().get(0).getParamsMap());
        assertEquals(clone, clone.getSelectedWorkflows().get(0).getConfiguration());
    }

    @Test
    void cloneConfiguration_disambiguatesName() {
        WorkflowConfiguration source = new WorkflowConfiguration();
        source.setId(5L);
        source.setName("Security");
        when(configurationRepository.findById(5L)).thenReturn(Optional.of(source));
        when(configurationRepository.existsByName("Copy of Security")).thenReturn(true);
        when(configurationRepository.existsByName("Copy of Security (2)")).thenReturn(false);

        WorkflowConfiguration clone = service.cloneConfiguration(5L);
        assertEquals("Copy of Security (2)", clone.getName());
    }
}

