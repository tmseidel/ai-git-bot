-- V28: Remove code-review chunking settings from ai_integrations.
-- These values are now workflow-level parameters on the review workflow.
-- No data migration — operators reconfigure these in the workflow UI.

ALTER TABLE ai_integrations DROP COLUMN IF EXISTS max_diff_chars_per_chunk;
ALTER TABLE ai_integrations DROP COLUMN IF EXISTS max_diff_chunks;
ALTER TABLE ai_integrations DROP COLUMN IF EXISTS retry_truncated_chunk_chars;
