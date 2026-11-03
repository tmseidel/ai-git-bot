package org.remus.giteabot.aiusage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Usage page queries against the Flyway schema: the row query leaves the raw payloads out,
 * sorting still works on the projection, and a full payload ({@code max-raw-payload-length=0})
 * far beyond 65535 characters is stored through Hibernate and read back by id.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-usage-persistence;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never",
        "ai-usage.raw-payloads-enabled=true", "ai-usage.max-raw-payload-length=0"
})
@ActiveProfiles("test")
class AiUsagePersistenceTest {

    @Autowired private AiUsageService service;
    @Autowired private AiUsageLogRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAllInBatch();
    }

    @Test
    void rowsComeWithoutPayloadsAndThePayloadLoadsInFullById() {
        String big = "{\"messages\":\"" + "x".repeat(150_000) + "\"}";
        service.recordUsage("small", "s#1", 2, 10, 1, 0, 0, "{\"a\":1}", "{\"b\":2}");
        service.recordUsage("big", "s#2", 3, 30, 3, 0, 0, big, null);

        Page<AiUsageRow> rows = service.findUsage(null, null, 0, 20, "inputTokens", false);

        assertThat(rows.getTotalElements()).isEqualTo(2);
        assertThat(rows.getContent()).extracting(AiUsageRow::getAiIntegrationName).containsExactly("big", "small");
        AiUsageRow bigRow = rows.getContent().getFirst();
        assertThat(bigRow.getRound()).isEqualTo(3);
        assertThat(bigRow.getSessionId()).isEqualTo("s#2");

        AiUsageRawPayload payload = service.findRawPayload(bigRow.getId()).orElseThrow();
        assertThat(payload.rawRequest()).isEqualTo(big);
        assertThat(payload.rawResponse()).isNull();
        assertThat(service.findRawPayload(-1L)).isEmpty();
    }
}
