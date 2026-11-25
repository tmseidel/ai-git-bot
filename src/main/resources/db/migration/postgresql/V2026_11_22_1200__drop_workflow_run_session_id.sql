-- ---------------------------------------------------------------------------------------------
-- Workflow runs (#599): drop the audit session id
--
-- The session id (owner/repo#n) was the old usage log's only way to correlate AI calls. Runs now
-- carry the repository, the pull request or issue number and the run id, so the copies on runs
-- and interactions are no longer needed.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE ai_interaction DROP COLUMN IF EXISTS session_id;
ALTER TABLE workflow_run DROP COLUMN IF EXISTS session_id;
