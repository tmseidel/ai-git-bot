ALTER TABLE pr_workflow_runs ADD COLUMN review_draft_json TEXT;
ALTER TABLE pr_workflow_runs ADD COLUMN review_draft_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pr_workflow_runs ADD COLUMN review_draft_status VARCHAR(24);
