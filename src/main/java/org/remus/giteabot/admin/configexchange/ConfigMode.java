package org.remus.giteabot.admin.configexchange;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether this instance runs from a configuration document, and what that means right
 * now. Everything here is derived from runtime facts, never from a feature flag:
 *
 * <ul>
 *   <li>the document is present when {@link ConfigSource} found one,</li>
 *   <li>{@code applied} is set by {@link ConfigImportRunner} when it actually imported,</li>
 *   <li>{@code ephemeral} is read from the JDBC URL ({@code jdbc:h2:mem:…}), so even a
 *       hand-written compose file cannot claim to be throw-away when it is not.</li>
 * </ul>
 *
 * <p>The UI turns this into one of three messages, which is what stops the banner from
 * promising a reset that no longer happens: a persistent instance that has already been
 * initialised keeps its data even though the document is still in the compose file.</p>
 */
@Component
public class ConfigMode {

    /** What the admin UI should tell the operator. */
    public enum State {
        /** No document — a normal self-hosted instance. */
        NOT_CONFIGURED,
        /** Document applied and the database is in memory: every start rebuilds it. */
        APPLIED_EPHEMERAL,
        /** Document applied into an empty persistent database; the database now wins. */
        APPLIED_PERSISTENT,
        /** A document is configured but the database already held data, so it was skipped. */
        IGNORED_DATABASE_NOT_EMPTY
    }

    private final boolean ephemeral;

    private volatile boolean documentPresent;
    private volatile boolean applied;

    @Getter
    private volatile int importedRows;

    public ConfigMode(@Value("${spring.datasource.url:}") String datasourceUrl) {
        this.ephemeral = datasourceUrl != null && datasourceUrl.contains(":mem:");
    }

    /** Called by the runner before it decides anything, so the banner can explain a skip. */
    public void markDocumentPresent() {
        this.documentPresent = true;
    }

    public void markApplied(int rows) {
        this.applied = true;
        this.importedRows = rows;
    }

    /** True when the database is thrown away on restart (in-memory H2). */
    public boolean isEphemeral() {
        return ephemeral;
    }

    public State state() {
        if (!documentPresent) {
            return State.NOT_CONFIGURED;
        }
        if (applied) {
            return ephemeral ? State.APPLIED_EPHEMERAL : State.APPLIED_PERSISTENT;
        }
        return State.IGNORED_DATABASE_NOT_EMPTY;
    }
}
