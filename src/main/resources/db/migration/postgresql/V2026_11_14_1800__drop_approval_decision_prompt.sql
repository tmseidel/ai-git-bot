-- Drop the operator-editable approval prompt.
--
-- The review decision is computed from the persisted findings and the configured severity
-- thresholds, so there is nothing left for a prompt to say. The parameter is gone from the
-- workflow schema; existing selections keep the stale row until this deletes it, so the saved
-- configuration no longer carries a value nothing reads.
--
-- Idempotent: the DELETE matches nothing once it has run.

DELETE FROM workflow_selection_params
WHERE name = 'formalReviewDecisionPrompt'
  AND workflow_selection_id IN (
      SELECT id FROM workflow_selections WHERE workflow_key = 'agentic-review'
  );
