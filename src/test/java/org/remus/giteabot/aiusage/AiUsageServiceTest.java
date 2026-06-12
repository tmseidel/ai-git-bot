package org.remus.giteabot.aiusage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock
    private AiUsageLogRepository usageRepository;

    @Mock
    private AiErrorLogRepository errorRepository;

    @InjectMocks
    private AiUsageService service;

    @Test
    void recordUsage_persistsEntryWithIntegrationAndSession() {
        service.recordUsage("my-openai", "owner/repo#42", 120, 35);

        ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(usageRepository).save(captor.capture());
        AiUsageLog entry = captor.getValue();
        assertEquals("my-openai", entry.getAiIntegrationName());
        assertEquals("owner/repo#42", entry.getSessionId());
        assertEquals(120, entry.getInputTokens());
        assertEquals(35, entry.getOutputTokens());
        assertNotNull(entry.getTimestamp());
    }

    @Test
    void recordUsage_neverPropagatesPersistenceFailures() {
        when(usageRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordUsage("my-openai", null, 1, 2));
    }

    @Test
    void clearUsage_deletesAllUsageEntries() {
        service.clearUsage();

        verify(usageRepository).deleteAllInBatch();
    }

    @Test
    void recordError_persistsMessageAndStackTrace() {
        service.recordError("my-anthropic", "owner/repo#7",
                new IllegalStateException("401 Unauthorized"));

        ArgumentCaptor<AiErrorLog> captor = ArgumentCaptor.forClass(AiErrorLog.class);
        verify(errorRepository).save(captor.capture());
        AiErrorLog entry = captor.getValue();
        assertEquals("my-anthropic", entry.getAiIntegrationName());
        assertEquals("owner/repo#7", entry.getSessionId());
        assertEquals("401 Unauthorized", entry.getErrorMessage());
        assertTrue(entry.getStackTrace().contains("IllegalStateException"));
        assertNotNull(entry.getTimestamp());
    }

    @Test
    void recordError_usesClassNameWhenMessageIsNull() {
        service.recordError("my-anthropic", null, new IllegalStateException());

        ArgumentCaptor<AiErrorLog> captor = ArgumentCaptor.forClass(AiErrorLog.class);
        verify(errorRepository).save(captor.capture());
        assertEquals(IllegalStateException.class.getName(), captor.getValue().getErrorMessage());
    }

    @Test
    void recordError_neverPropagatesPersistenceFailures() {
        when(errorRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordError("x", null, new RuntimeException("boom")));
    }

    @Test
    void findUsage_fallsBackToTimestampForUnknownSortColumn() {
        Page<AiUsageLog> page = new PageImpl<>(List.of());
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        when(usageRepository.findByTimestampBetween(any(), any(), captor.capture()))
                .thenReturn(page);

        service.findUsage(null, null, 0, "evil'); DROP TABLE", false);

        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("timestamp", order.getProperty());
        assertEquals(Sort.Direction.DESC, order.getDirection());
        assertEquals(AiUsageService.PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void findErrors_appliesRequestedSortAscending() {
        Page<AiErrorLog> page = new PageImpl<>(List.of());
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        when(errorRepository.findByTimestampBetween(any(), any(), captor.capture()))
                .thenReturn(page);

        service.findErrors(Instant.EPOCH, Instant.now(), 2, "aiIntegrationName", true);

        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("aiIntegrationName", order.getProperty());
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertEquals(2, captor.getValue().getPageNumber());
    }
}
