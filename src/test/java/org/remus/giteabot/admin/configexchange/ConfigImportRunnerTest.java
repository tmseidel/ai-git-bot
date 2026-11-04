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
 * unconfigured instance whose operator can finish at {@code /setup}. It must also say
 * <em>why</em> it did not apply the document — a failed import is not a database that was
 * already full.
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

        runner().afterSingletonsInstantiated();

        verify(service, never()).importDocument(any());
        assertEquals(ConfigMode.State.NOT_CONFIGURED, mode.state());
    }

    @Test
    void appliesTheDocumentIntoAnEmptyDatabase() {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(true);
        when(service.importDocument(any())).thenReturn(3);

        runner().afterSingletonsInstantiated();

        assertEquals(ConfigMode.State.APPLIED_EPHEMERAL, mode.state());
        assertEquals(3, mode.getImportedRows());
    }

    @Test
    void skipsWhenTheDatabaseIsNotEmpty() {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(false);

        runner().afterSingletonsInstantiated();

        verify(service, never()).importDocument(any());
        assertEquals(ConfigMode.State.IGNORED_DATABASE_NOT_EMPTY, mode.state());
    }

    @Test
    void malformedJsonStartsUnconfiguredAndSaysWhy(CapturedOutput output) {
        when(source.read()).thenReturn(Optional.of("{ not json"));
        when(adminService.isSetupRequired()).thenReturn(true);

        assertDoesNotThrow(() -> runner().afterSingletonsInstantiated());

        verify(service, never()).importDocument(any());
        assertTrue(output.getOut().contains("not valid JSON"), output.getOut());
        assertEquals(ConfigMode.State.IMPORT_FAILED, mode.state(),
                "a document that could not be parsed is not a database that already had data");
    }

    @Test
    void failedImportStartsUnconfiguredAndSaysSo(CapturedOutput output) {
        when(source.read()).thenReturn(Optional.of(document()));
        when(adminService.isSetupRequired()).thenReturn(true);
        when(service.importDocument(any()))
                .thenThrow(new IllegalArgumentException("bots[0]: aiIntegration missing"));

        assertDoesNotThrow(() -> runner().afterSingletonsInstantiated());

        assertTrue(output.getOut().contains("Config import failed"), output.getOut());
        assertTrue(output.getOut().contains("aiIntegration missing"), output.getOut());
        assertEquals(ConfigMode.State.IMPORT_FAILED, mode.state());
    }

    private static String document() {
        return "{\"bots\": []}";
    }
}
