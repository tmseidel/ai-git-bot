package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.tools.ToolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotToolSelectionServiceTest {

    @Mock
    private BotToolConfigurationRepository configurationRepository;

    @Mock
    private BotToolSelectionRepository selectionRepository;

    @Mock
    private BuiltinToolRegistry builtinToolRegistry;

    @InjectMocks
    private BotToolSelectionService service;

    private BotToolConfiguration configuration(long id, boolean defaultEntry) {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setId(id);
        configuration.setName(defaultEntry ? "Default" : "Java");
        configuration.setDefaultEntry(defaultEntry);
        return configuration;
    }

    private BotToolSelection selection(BotToolConfiguration configuration, String name, String kind) {
        BotToolSelection row = new BotToolSelection();
        row.setConfiguration(configuration);
        row.setToolName(name);
        row.setToolKind(kind);
        return row;
    }

    @Test
    void loadAvailableTools_marksPersistedRowsAsSelected() {
        BotToolConfiguration config = configuration(1L, false);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(config));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("write-file", ToolKind.FILE, "desc-write"),
                new BuiltinToolRegistry.BuiltinTool("cat",        ToolKind.CONTEXT, "desc-cat"),
                new BuiltinToolRegistry.BuiltinTool("mvn",        ToolKind.VALIDATION, "desc-mvn")
        ));
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of(
                selection(config, "mvn", "VALIDATION")
        ));

        List<BotToolSelectionRow> rows = service.loadAvailableTools(1L);

        assertEquals(3, rows.size());
        BotToolSelectionRow mvn = rows.stream().filter(r -> r.toolName().equals("mvn")).findFirst().orElseThrow();
        BotToolSelectionRow cat = rows.stream().filter(r -> r.toolName().equals("cat")).findFirst().orElseThrow();
        assertTrue(mvn.selected());
        assertFalse(cat.selected());
        assertEquals("VALIDATION", mvn.toolKind());
        assertEquals("desc-mvn", mvn.description());
    }

    @Test
    void loadAvailableTools_keepsUnknownPersistedRowsVisible() {
        BotToolConfiguration config = configuration(1L, false);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(config));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("cat", ToolKind.CONTEXT, "desc-cat")
        ));
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of(
                selection(config, "obsolete-tool", "FILE")
        ));

        List<BotToolSelectionRow> rows = service.loadAvailableTools(1L);

        assertEquals(2, rows.size());
        BotToolSelectionRow ghost = rows.stream()
                .filter(r -> r.toolName().equals("obsolete-tool")).findFirst().orElseThrow();
        assertTrue(ghost.selected());
        assertEquals("FILE", ghost.toolKind());
    }

    @Test
    void saveSelection_defaultConfiguration_rejected() {
        BotToolConfiguration defaultConfig = configuration(1L, true);
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(defaultConfig));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.saveSelection(1L, List.of("mvn")));

        assertTrue(exception.getMessage().contains("default tool configuration"));
        verify(selectionRepository, never()).deleteByConfigurationId(any());
        verify(selectionRepository, never()).saveAll(anyList());
    }

    @Test
    void saveSelection_replacesRows_andNormalizesNames() {
        BotToolConfiguration config = configuration(2L, false);
        when(configurationRepository.findById(2L)).thenReturn(Optional.of(config));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("mvn",        ToolKind.VALIDATION, ""),
                new BuiltinToolRegistry.BuiltinTool("write-file", ToolKind.FILE, "")
        ));
        when(selectionRepository.findByConfigurationId(2L)).thenReturn(new ArrayList<>());

        service.saveSelection(2L, List.of("  MVN  ", "write-file", "", "MVN", "  "));

        verify(selectionRepository).deleteByConfigurationId(2L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotToolSelection>> captor = ArgumentCaptor.forClass(List.class);
        verify(selectionRepository).saveAll(captor.capture());
        List<BotToolSelection> saved = captor.getValue();
        assertEquals(2, saved.size(), "duplicates and blanks must be dropped");
        assertEquals("mvn", saved.get(0).getToolName());
        assertEquals("VALIDATION", saved.get(0).getToolKind());
        assertEquals("write-file", saved.get(1).getToolName());
        assertEquals("FILE", saved.get(1).getToolKind());
        assertEquals(config, saved.get(0).getConfiguration());
    }

    @Test
    void saveSelection_dropsCompletelyUnknownTools() {
        BotToolConfiguration config = configuration(2L, false);
        when(configurationRepository.findById(2L)).thenReturn(Optional.of(config));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("mvn", ToolKind.VALIDATION, "")
        ));
        when(selectionRepository.findByConfigurationId(2L)).thenReturn(new ArrayList<>());

        service.saveSelection(2L, List.of("mvn", "this-tool-does-not-exist"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotToolSelection>> captor = ArgumentCaptor.forClass(List.class);
        verify(selectionRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("mvn", captor.getValue().get(0).getToolName());
    }

    @Test
    void saveSelection_preservesKindOfPersistedButUnknownTool() {
        BotToolConfiguration config = configuration(2L, false);
        when(configurationRepository.findById(2L)).thenReturn(Optional.of(config));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of());
        when(selectionRepository.findByConfigurationId(2L)).thenReturn(List.of(
                selection(config, "legacy-tool", "FILE")
        ));

        service.saveSelection(2L, List.of("legacy-tool"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotToolSelection>> captor = ArgumentCaptor.forClass(List.class);
        verify(selectionRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("FILE", captor.getValue().get(0).getToolKind());
    }

    @Test
    void allowedBuiltinTools_returnsLowercaseSet() {
        BotToolConfiguration config = configuration(3L, false);
        when(selectionRepository.findByConfigurationId(3L)).thenReturn(List.of(
                selection(config, "MVN", "VALIDATION"),
                selection(config, "Write-File", "FILE")
        ));

        Set<String> allowed = service.allowedBuiltinTools(config);

        assertEquals(Set.of("mvn", "write-file"), allowed);
    }

    @Test
    void allowedBuiltinTools_nullConfiguration_returnsEmptySet() {
        assertTrue(service.allowedBuiltinTools(null).isEmpty());
        verify(selectionRepository, never()).findByConfigurationId(eq(0L));
    }
}
