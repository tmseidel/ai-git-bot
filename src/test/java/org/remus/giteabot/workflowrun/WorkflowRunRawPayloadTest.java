package org.remus.giteabot.workflowrun;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.aiusage.AiInteractionRepository;
import org.remus.giteabot.aiusage.AiToolCallRepository;
import org.remus.giteabot.aiusage.AiUsageRawPayload;
import org.remus.giteabot.config.AiUsageProperties;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Raw payloads are only shown while raw payload capture is enabled. */
class WorkflowRunRawPayloadTest {

    private final AiInteractionRepository interactionRepository = mock(AiInteractionRepository.class);
    private final AiUsageProperties properties = new AiUsageProperties();
    private final WorkflowRunQueryService service = new WorkflowRunQueryService(mock(WorkflowRunRepository.class),
            interactionRepository, mock(AiToolCallRepository.class), properties, mock(TriggerLinks.class),
            mock(tools.jackson.databind.ObjectMapper.class),
            mock(jakarta.persistence.EntityManager.class));

    @Test
    void storedPayloadsAreReadWhenCaptureIsEnabled() {
        properties.setRawPayloadsEnabled(true);
        AiUsageRawPayload payload = new AiUsageRawPayload("req", "res");
        when(interactionRepository.findRawPayloadById(5L)).thenReturn(Optional.of(payload));

        assertThat(service.rawPayload(5L)).contains(payload);
    }

    @Test
    void storedPayloadsAreNotShownWhenCaptureIsDisabled() {
        properties.setRawPayloadsEnabled(false);

        assertThat(service.rawPayload(5L)).isEmpty();
        verifyNoInteractions(interactionRepository);
    }
}
