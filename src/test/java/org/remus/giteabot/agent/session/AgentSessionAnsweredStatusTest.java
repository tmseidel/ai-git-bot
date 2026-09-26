package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the V53 {@code ANSWERED} status survives a real write/read cycle on the
 * default (H2) schema: before the migration the CHECK constraint rejected the value, so a run
 * that answered an issue without touching the repository could not have been recorded.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentSessionAnsweredStatusTest {

    @Autowired private AgentSessionRepository repository;
    @Autowired private TransactionTemplate tx;

    @Test
    void answeredStatusIsPersistedAndReadBack() {
        Long id = tx.execute(s -> {
            AgentSession session = new AgentSession("owner", "repo", 9_000_777L, "Read-only question");
            session.setStatus(AgentSession.AgentSessionStatus.ANSWERED);
            return repository.save(session).getId();
        });
        assertThat(id).isNotNull();

        AgentSession reloaded = tx.execute(s -> repository.findById(id).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(AgentSession.AgentSessionStatus.ANSWERED);
    }
}
