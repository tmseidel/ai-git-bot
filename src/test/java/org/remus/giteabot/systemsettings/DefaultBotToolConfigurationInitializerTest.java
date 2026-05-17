package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.tools.ToolKind;
import org.remus.giteabot.systemsettings.DefaultBotToolConfigurationInitializer.DefaultBotToolConfigurationBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBotToolConfigurationInitializerTest {

    @Mock
    private BotToolConfigurationRepository configurationRepository;

    @Mock
    private BotToolSelectionRepository selectionRepository;

    @Mock
    private BuiltinToolRegistry builtinToolRegistry;

    private DefaultBotToolConfigurationBootstrap bootstrap() {
        return new DefaultBotToolConfigurationBootstrap(
                configurationRepository, selectionRepository, builtinToolRegistry);
    }

    private BotToolConfiguration defaultConfig() {
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setId(1L);
        configuration.setName(DefaultBotToolConfigurationInitializer.DEFAULT_CONFIGURATION_NAME);
        configuration.setDefaultEntry(true);
        return configuration;
    }

    @Test
    void firstStart_createsDefaultAndAddsAllTools() {
        when(configurationRepository.findByDefaultEntryTrue()).thenReturn(Optional.empty());
        when(configurationRepository.save(any(BotToolConfiguration.class)))
                .thenAnswer(invocation -> {
                    BotToolConfiguration argument = invocation.getArgument(0);
                    argument.setId(1L);
                    return argument;
                });
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("write-file", ToolKind.FILE, ""),
                new BuiltinToolRegistry.BuiltinTool("cat",        ToolKind.CONTEXT, ""),
                new BuiltinToolRegistry.BuiltinTool("mvn",        ToolKind.VALIDATION, "")
        ));
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(new ArrayList<>());

        bootstrap().ensureDefault();

        ArgumentCaptor<BotToolConfiguration> configCaptor = ArgumentCaptor.forClass(BotToolConfiguration.class);
        verify(configurationRepository).save(configCaptor.capture());
        assertTrue(configCaptor.getValue().isDefaultEntry());
        assertEquals("Default", configCaptor.getValue().getName());

        ArgumentCaptor<BotToolSelection> selectionCaptor = ArgumentCaptor.forClass(BotToolSelection.class);
        verify(selectionRepository, org.mockito.Mockito.times(3)).save(selectionCaptor.capture());
        List<String> saved = selectionCaptor.getAllValues().stream().map(BotToolSelection::getToolName).toList();
        assertEquals(List.of("write-file", "cat", "mvn"), saved);
    }

    @Test
    void subsequentStart_addsOnlyNewTools_andDoesNotRemoveAnything() {
        BotToolConfiguration existing = defaultConfig();
        when(configurationRepository.findByDefaultEntryTrue()).thenReturn(Optional.of(existing));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("write-file", ToolKind.FILE, ""),
                new BuiltinToolRegistry.BuiltinTool("cat",        ToolKind.CONTEXT, ""),
                new BuiltinToolRegistry.BuiltinTool("brand-new",  ToolKind.CONTEXT, "")
        ));

        BotToolSelection writeFile = new BotToolSelection();
        writeFile.setConfiguration(existing);
        writeFile.setToolName("write-file");
        writeFile.setToolKind("FILE");
        BotToolSelection cat = new BotToolSelection();
        cat.setConfiguration(existing);
        cat.setToolName("cat");
        cat.setToolKind("CONTEXT");
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of(writeFile, cat));

        bootstrap().ensureDefault();

        verify(configurationRepository, never()).save(any());
        ArgumentCaptor<BotToolSelection> captor = ArgumentCaptor.forClass(BotToolSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertEquals("brand-new", captor.getValue().getToolName());
        assertEquals("CONTEXT", captor.getValue().getToolKind());
        assertEquals(existing, captor.getValue().getConfiguration());
    }

    @Test
    void idempotent_whenNothingChanged() {
        BotToolConfiguration existing = defaultConfig();
        when(configurationRepository.findByDefaultEntryTrue()).thenReturn(Optional.of(existing));
        when(builtinToolRegistry.builtinTools()).thenReturn(List.of(
                new BuiltinToolRegistry.BuiltinTool("cat", ToolKind.CONTEXT, "")
        ));
        BotToolSelection persisted = new BotToolSelection();
        persisted.setConfiguration(existing);
        persisted.setToolName("cat");
        persisted.setToolKind("CONTEXT");
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of(persisted));

        bootstrap().ensureDefault();

        verify(configurationRepository, never()).save(any());
        verify(selectionRepository, never()).save(any());
    }
}
