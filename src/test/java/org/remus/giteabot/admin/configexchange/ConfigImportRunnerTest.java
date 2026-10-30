package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.remus.giteabot.admin.AdminService;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The runner must never take the application down: a broken document leaves an
 * unconfigured instance whose operator can finish at {@code /setup}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfigImportRunnerTest {

    private final ConfigSource source = mock(ConfigSource.class);
    private final ConfigExchangeService service = mock(ConfigExchangeService.class);
    private final AdminService adminService = mock(AdminService.class);
    private final ConfigMode mode = new ConfigMode("jdbc:h2:mem:test");

    private ConfigImportRunner runner() {
        return new ConfigImportRunner(source, service, adminService, mode);
    }

    @Test
    void doesNothingWithoutADocument() {
        when(source.read()).thenReturn(Optional.empty());

        runner().run(null);

        verify(service, never()).importDocument(any());
        assertEquals(ConfigMode.State.NOT_CONFIGURED, mode.state());
    }

    @Test
    void appliesTheDocumentIntoAnEmptyDatabase() {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(true);
        when(service.importDocument(any())).thenReturn(3);

        runner().run(null);

        assertEquals(ConfigMode.State.APPLIED_EPHEMERAL, mode.state());
        assertEquals(3, mode.getImportedRows());
    }

    @Test
    void skipsWhenTheDatabaseIsNotEmpty() {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(false);

        runner().run(null);

        verify(service, never()).importDocument(any());
        assertEquals(ConfigMode.State.IGNORED_DATABASE_NOT_EMPTY, mode.state());
    }

    @Test
    void malformedJsonStartsUnconfigured(CapturedOutput output) {
        when(source.read()).thenReturn(Optional.of("{ not json"));
        when(adminService.isSetupRequired()).thenReturn(true);

        assertDoesNotThrow(() -> runner().run(null));

        verify(service, never()).importDocument(any());
        assertTrue(output.getOut().contains("not valid JSON"), output.getOut());
    }

    @Test
    void failedImportStartsUnconfiguredAndSaysSo(CapturedOutput output) {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(true);
        when(service.importDocument(any()))
                .thenThrow(new IllegalArgumentException("bots[0]: aiIntegration missing"));

        assertDoesNotThrow(() -> runner().run(null));

        assertTrue(output.getOut().contains("Config import failed"), output.getOut());
        assertTrue(output.getOut().contains("aiIntegration missing"), output.getOut());
        assertEquals(ConfigMode.State.IGNORED_DATABASE_NOT_EMPTY, mode.state());
    }

    private static String document() {
        return "{\"bots\": []}";
    }
}
