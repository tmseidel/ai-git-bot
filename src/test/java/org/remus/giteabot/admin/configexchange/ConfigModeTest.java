package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The banner text follows from these states, and the states follow from runtime facts —
 * which is what keeps a persistent instance from promising a reset it does not do.
 */
class ConfigModeTest {

    @Test
    void inMemoryDatabaseIsRecognisedFromTheJdbcUrl() {
        assertTrue(new ConfigMode("jdbc:h2:mem:giteabot;DB_CLOSE_DELAY=-1").isEphemeral());
        assertFalse(new ConfigMode("jdbc:h2:file:./data/giteabot;AUTO_SERVER=TRUE").isEphemeral());
        assertFalse(new ConfigMode("jdbc:postgresql://db:5432/giteabot").isEphemeral());
        assertFalse(new ConfigMode("").isEphemeral());
    }

    @Test
    void stateFollowsDocumentDatabaseAndImport() {
        ConfigMode mode = new ConfigMode("jdbc:postgresql://db:5432/giteabot");
        assertEquals(ConfigMode.State.NOT_CONFIGURED, mode.state());

        mode.markDocumentPresent();
        assertEquals(ConfigMode.State.IGNORED_DATABASE_NOT_EMPTY, mode.state(),
                "document present but not applied means the database already had data");

        mode.markApplied(4);
        assertEquals(ConfigMode.State.APPLIED_PERSISTENT, mode.state());
    }

    @Test
    void appliedDocumentOnAResetDatabaseIsReportedAsThrowAway() {
        ConfigMode mode = new ConfigMode("jdbc:h2:mem:giteabot");
        mode.markDocumentPresent();
        mode.markApplied(6);

        assertEquals(ConfigMode.State.APPLIED_EPHEMERAL, mode.state());
    }
}
