-- ---------------------------------------------------------------------------------------------
-- Lesson retrieval for agentic review (step 4 of the learning plan)
--
-- lesson_usage rows are written when a review receives a lesson revision. One invocation records
-- each revision once, so a retried recording can never count a delivery twice. The output snapshot
-- records whether learned guidance was supplied (or why not), the exact revisions and the hash of
-- the guidance block, so a review whose retrieval failed is never read as a lesson's evidence.
--
-- Idempotent: every statement can be re-applied.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE workflow_output_snapshots ADD COLUMN IF NOT EXISTS lesson_selection VARCHAR(16);
ALTER TABLE workflow_output_snapshots ADD COLUMN IF NOT EXISTS lesson_revisions VARCHAR(1000);
ALTER TABLE workflow_output_snapshots ADD COLUMN IF NOT EXISTS lesson_context_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lesson_usage_invocation_revision
    ON lesson_usage (invocation_id, revision_id);

CREATE INDEX IF NOT EXISTS idx_lesson_usage_output ON lesson_usage (output_id);
