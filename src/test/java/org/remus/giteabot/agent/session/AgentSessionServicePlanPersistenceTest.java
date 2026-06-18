package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Step 7.1 — verifies that {@link AgentSessionService#recordPlan} writes the
 * latest plan summary, raw JSON and timestamp to the managed proxy (for DB
 * persistence) and returns that managed entity.
 *
 * <p>Phase 1 of the AgentSession state cleanup removed the dual-mutation
 * workaround: the caller's (possibly detached) object is no longer mutated,
 * so callers must rebind to the returned managed entity.</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServicePlanPersistenceTest {

    @Mock private AgentSessionRepository repository;

    @Test
    void recordPlanWritesAllThreeColumnsToManagedEntityAndReturnsIt() {
        AgentSession managed = new AgentSession("o", "r", 1L, "title");
        managed.setId(42L);
        when(repository.getReferenceById(42L)).thenReturn(managed);

        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        session.setId(42L);

        Instant before = Instant.now();
        AgentSession result = svc.recordPlan(session, "short summary", "{\"summary\":\"short summary\"}");

        // The managed entity is mutated and returned
        assertThat(result).isSameAs(managed);
        assertThat(result.getLastPlanSummary()).isEqualTo("short summary");
        assertThat(result.getLastPlanJson()).isEqualTo("{\"summary\":\"short summary\"}");
        assertThat(result.getLastPlanAt()).isAfterOrEqualTo(before);

        // The caller's detached object is no longer mutated
        assertThat(session.getLastPlanSummary()).isNull();
        assertThat(session.getLastPlanJson()).isNull();
        assertThat(session.getLastPlanAt()).isNull();
    }

    @Test
    void recordPlanOverwritesPreviousValuesOnRepeatedCalls() {
        AgentSession managed = new AgentSession("o", "r", 1L, "title");
        managed.setId(42L);
        when(repository.getReferenceById(42L)).thenReturn(managed);

        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        session.setId(42L);

        svc.recordPlan(session, "first", "{\"v\":1}");
        svc.recordPlan(session, "second", "{\"v\":2}");

        // The managed proxy reflects the latest values
        assertThat(managed.getLastPlanSummary()).isEqualTo("second");
        assertThat(managed.getLastPlanJson()).isEqualTo("{\"v\":2}");
    }
}
