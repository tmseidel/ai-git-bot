package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.BotRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotToolConfigurationServiceTest {

    @Mock
    private BotToolConfigurationRepository configurationRepository;

    @Mock
    private BotToolSelectionRepository selectionRepository;

    @Mock
    private BotRepository botRepository;

    @InjectMocks
    private BotToolConfigurationService service;

    @BeforeEach
    void setUp() {
        // Repositories are reset between tests by Mockito.
    }

    @Test
    void save_rejectsBlankName() {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setName("   ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.save(configuration));

        assertEquals("Name is required", exception.getMessage());
        verify(configurationRepository, never()).save(any());
    }

    @Test
    void save_rejectsDuplicateName_forNewConfiguration() {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setName("Java");
        when(configurationRepository.existsByName("Java")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.save(configuration));

        assertEquals("A tool configuration with this name already exists", exception.getMessage());
        verify(configurationRepository, never()).save(any());
    }

    @Test
    void save_newConfigurationCannotClaimDefaultFlag() {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setName("Tries to be default");
        configuration.setDefaultEntry(true);
        when(configurationRepository.existsByName("Tries to be default")).thenReturn(false);
        when(configurationRepository.save(any(BotToolConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BotToolConfiguration saved = service.save(configuration);

        assertFalse(saved.isDefaultEntry(),
                "save() must clear the default flag on new configurations");
    }

    @Test
    void save_defaultConfiguration_cannotBeRenamed() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);

        BotToolConfiguration update = new BotToolConfiguration();
        update.setId(1L);
        update.setName("Renamed");
        update.setDefaultEntry(true);

        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.save(update));

        assertEquals("The default tool configuration cannot be renamed", exception.getMessage());
        verify(configurationRepository, never()).save(any());
    }

    @Test
    void save_defaultConfiguration_retainsDefaultFlagEvenIfCallerClearsIt() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);

        BotToolConfiguration update = new BotToolConfiguration();
        update.setId(1L);
        update.setName("Default"); // same name
        update.setDefaultEntry(false); // attempt to clear the flag

        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));
        when(configurationRepository.existsByNameAndIdNot("Default", 1L)).thenReturn(false);
        when(configurationRepository.save(any(BotToolConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BotToolConfiguration saved = service.save(update);

        assertTrue(saved.isDefaultEntry(),
                "Default flag must not be lost via a regular update");
    }

    @Test
    void save_existingConfiguration_preservesSelectedTools() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(2L);
        persisted.setName("Java");
        persisted.setDefaultEntry(false);
        BotToolSelection selectedTool = new BotToolSelection();
        selectedTool.setConfiguration(persisted);
        selectedTool.setToolName("mvn");
        selectedTool.setToolKind("VALIDATION");
        persisted.setSelectedTools(List.of(selectedTool));

        BotToolConfiguration update = new BotToolConfiguration();
        update.setId(2L);
        update.setName("  Java Renamed  ");

        when(configurationRepository.findById(2L)).thenReturn(Optional.of(persisted));
        when(configurationRepository.existsByNameAndIdNot("Java Renamed", 2L)).thenReturn(false);
        when(configurationRepository.save(any(BotToolConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BotToolConfiguration saved = service.save(update);

        assertSame(persisted, saved);
        assertEquals("Java Renamed", saved.getName());
        assertEquals(List.of(selectedTool), saved.getSelectedTools());
    }

    @Test
    void deleteById_defaultConfiguration_throws() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(1L);
        persisted.setName("Default");
        persisted.setDefaultEntry(true);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(persisted));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.deleteById(1L));

        assertEquals("The default tool configuration cannot be deleted", exception.getMessage());
        verify(configurationRepository, never()).delete(any());
        verify(selectionRepository, never()).deleteByConfigurationId(any());
    }

    @Test
    void deleteById_nonDefault_deletesSelectionsAndConfiguration() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(7L);
        persisted.setName("Java");
        persisted.setDefaultEntry(false);
        when(configurationRepository.findById(7L)).thenReturn(Optional.of(persisted));
        when(botRepository.findByToolConfigurationId(7L)).thenReturn(List.of());

        service.deleteById(7L);

        verify(selectionRepository).deleteByConfigurationId(7L);
        verify(configurationRepository).delete(persisted);
    }

    @Test
    void deleteById_configurationUsedByBots_throwsWithBotNames() {
        BotToolConfiguration persisted = new BotToolConfiguration();
        persisted.setId(7L);
        persisted.setName("Java");
        persisted.setDefaultEntry(false);
        org.remus.giteabot.admin.Bot bot = new org.remus.giteabot.admin.Bot();
        bot.setName("Maven Bot");
        when(configurationRepository.findById(7L)).thenReturn(Optional.of(persisted));
        when(botRepository.findByToolConfigurationId(7L)).thenReturn(List.of(bot));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.deleteById(7L));

        assertTrue(exception.getMessage().contains("Maven Bot"));
        verify(selectionRepository, never()).deleteByConfigurationId(any());
        verify(configurationRepository, never()).delete(any());
    }

    @Test
    void cloneConfiguration_copiesNameAndSelections() {
        BotToolConfiguration source = new BotToolConfiguration();
        source.setId(5L);
        source.setName("Java");

        BotToolSelection original = new BotToolSelection();
        original.setConfiguration(source);
        original.setToolName("mvn");
        original.setToolKind("VALIDATION");
        source.setSelectedTools(List.of(original));

        when(configurationRepository.findById(5L)).thenReturn(Optional.of(source));
        when(configurationRepository.existsByName("Copy of Java")).thenReturn(false);

        BotToolConfiguration clone = service.cloneConfiguration(5L);

        assertNotSame(source, clone);
        assertEquals("Copy of Java", clone.getName());
        assertFalse(clone.isDefaultEntry());
        assertEquals(1, clone.getSelectedTools().size());
        BotToolSelection copied = clone.getSelectedTools().get(0);
        assertEquals("mvn", copied.getToolName());
        assertEquals("VALIDATION", copied.getToolKind());
        assertEquals(clone, copied.getConfiguration());
        verify(configurationRepository, never()).save(any());
    }

    @Test
    void cloneConfiguration_disambiguatesName() {
        BotToolConfiguration source = new BotToolConfiguration();
        source.setId(5L);
        source.setName("Java");

        when(configurationRepository.findById(5L)).thenReturn(Optional.of(source));
        when(configurationRepository.existsByName("Copy of Java")).thenReturn(true);
        when(configurationRepository.existsByName("Copy of Java (2)")).thenReturn(false);

        BotToolConfiguration clone = service.cloneConfiguration(5L);

        assertEquals("Copy of Java (2)", clone.getName());
    }

    @Test
    void save_persistsTrimmedName() {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setName("  Custom  ");
        when(configurationRepository.existsByName("Custom")).thenReturn(false);
        when(configurationRepository.save(any(BotToolConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.save(configuration);

        ArgumentCaptor<BotToolConfiguration> captor = ArgumentCaptor.forClass(BotToolConfiguration.class);
        verify(configurationRepository).save(captor.capture());
        assertEquals("Custom", captor.getValue().getName());
    }
}


