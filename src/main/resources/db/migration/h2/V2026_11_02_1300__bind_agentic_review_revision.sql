ALTER TABLE pr_workflow_runs ADD COLUMN review_head_sha VARCHAR(64);
ALTER TABLE pr_workflow_runs ADD COLUMN review_base_sha VARCHAR(64);
ALTER TABLE pr_workflow_runs ADD COLUMN review_diff_hash VARCHAR(71);
