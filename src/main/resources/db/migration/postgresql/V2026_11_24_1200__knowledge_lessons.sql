-- ---------------------------------------------------------------------------------------------
-- Versioned lessons for agentic review (step 3 of the learning plan)
--
-- A lesson (L<n>) belongs to one Git integration, bot, repository and workflow (agentic-review).
-- Its wording and path scope live in immutable revisions (L<n>@<m>); editing writes the next
-- revision, so an activation always names the exact text a maintainer read. Evidence rows point at
-- the feedback a revision rests on, by foreign key, so a retraction is visible on every lesson.
-- learning_events is the append-only history; its source_key makes a redelivered Git comment find
-- the event it already caused. lesson_usage is written by lesson retrieval and only counted here.
--
-- Runtime data: nothing here is configuration, and nothing references the replaceable workflow
-- configuration rows, so changing or deleting a configuration never touches knowledge.
--
-- Idempotent: every statement can be re-applied.
-- ---------------------------------------------------------------------------------------------

INSERT INTO reference_sequences (name, next_value)
SELECT 'knowledge_lesson', 1
WHERE NOT EXISTS (SELECT 1 FROM reference_sequences WHERE name = 'knowledge_lesson');

CREATE TABLE IF NOT EXISTS knowledge_lessons (
    id                     BIGSERIAL PRIMARY KEY,
    reference              VARCHAR(32)  NOT NULL,
    git_integration_id     BIGINT       NOT NULL,
    bot_id                 BIGINT       NOT NULL,
    repo_owner             VARCHAR(255) NOT NULL,
    repo_name              VARCHAR(255) NOT NULL,
    provider_repository_id VARCHAR(255),
    workflow_key           VARCHAR(64)  NOT NULL,
    state                  VARCHAR(16)  NOT NULL DEFAULT 'CANDIDATE',
    latest_revision        INTEGER      NOT NULL,
    active_revision        INTEGER,
    review_required        BOOLEAN      NOT NULL DEFAULT FALSE,
    review_reason          VARCHAR(500),
    created_by             VARCHAR(255) NOT NULL,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_knowledge_lessons_reference UNIQUE (reference)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_lessons_scope
    ON knowledge_lessons (git_integration_id, bot_id, repo_owner, repo_name, workflow_key, state);

CREATE TABLE IF NOT EXISTS knowledge_lesson_revisions (
    id                    BIGSERIAL PRIMARY KEY,
    lesson_id             BIGINT        NOT NULL,
    revision_number       INTEGER       NOT NULL,
    state                 VARCHAR(16)   NOT NULL DEFAULT 'CANDIDATE',
    lesson_text           VARCHAR(2000) NOT NULL,
    path_scopes           VARCHAR(2100),
    category              VARCHAR(64),
    source_commit_sha     VARCHAR(64),
    recheck_after         TIMESTAMP,
    content_hash          VARCHAR(64)   NOT NULL,
    created_by            VARCHAR(255)  NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    activation_provenance VARCHAR(32),
    activated_by          VARCHAR(255),
    activated_at          TIMESTAMP,
    ended_by              VARCHAR(255),
    ended_at              TIMESTAMP,
    CONSTRAINT uk_knowledge_lesson_revisions_number UNIQUE (lesson_id, revision_number),
    CONSTRAINT fk_knowledge_lesson_revisions_lesson
        FOREIGN KEY (lesson_id) REFERENCES knowledge_lessons(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_lesson_evidence (
    id                 BIGSERIAL PRIMARY KEY,
    revision_id        BIGINT      NOT NULL,
    lesson_id          BIGINT      NOT NULL,
    feedback_id        BIGINT      NOT NULL,
    feedback_reference VARCHAR(32) NOT NULL,
    target_reference   VARCHAR(32) NOT NULL,
    created_at         TIMESTAMP   NOT NULL,
    CONSTRAINT uk_knowledge_lesson_evidence_revision_feedback UNIQUE (revision_id, feedback_id),
    CONSTRAINT fk_knowledge_lesson_evidence_revision
        FOREIGN KEY (revision_id) REFERENCES knowledge_lesson_revisions(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_lesson_evidence_lesson
        FOREIGN KEY (lesson_id) REFERENCES knowledge_lessons(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_lesson_evidence_feedback
        FOREIGN KEY (feedback_id) REFERENCES workflow_feedback(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_knowledge_lesson_evidence_feedback
    ON knowledge_lesson_evidence (feedback_id);

CREATE INDEX IF NOT EXISTS idx_knowledge_lesson_evidence_lesson
    ON knowledge_lesson_evidence (lesson_id);

CREATE TABLE IF NOT EXISTS learning_events (
    id                BIGSERIAL PRIMARY KEY,
    lesson_id         BIGINT       NOT NULL,
    revision_number   INTEGER,
    event_type        VARCHAR(24)  NOT NULL,
    actor             VARCHAR(255) NOT NULL,
    detail            VARCHAR(1000),
    related_reference VARCHAR(32),
    source_key        VARCHAR(64),
    acknowledgement   VARCHAR(16),
    created_at        TIMESTAMP    NOT NULL,
    acknowledged_at   TIMESTAMP,
    CONSTRAINT uk_learning_events_source UNIQUE (source_key),
    CONSTRAINT fk_learning_events_lesson
        FOREIGN KEY (lesson_id) REFERENCES knowledge_lessons(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_learning_events_lesson ON learning_events (lesson_id);

CREATE TABLE IF NOT EXISTS lesson_usage (
    id                BIGSERIAL PRIMARY KEY,
    lesson_id         BIGINT      NOT NULL,
    revision_id       BIGINT      NOT NULL,
    output_id         BIGINT,
    invocation_id     VARCHAR(36) NOT NULL,
    selection_reason  VARCHAR(500),
    prompt_block_hash VARCHAR(64),
    created_at        TIMESTAMP   NOT NULL,
    CONSTRAINT fk_lesson_usage_lesson
        FOREIGN KEY (lesson_id) REFERENCES knowledge_lessons(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_usage_revision
        FOREIGN KEY (revision_id) REFERENCES knowledge_lesson_revisions(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_usage_output
        FOREIGN KEY (output_id) REFERENCES workflow_outputs(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_lesson_usage_lesson ON lesson_usage (lesson_id, revision_id);
