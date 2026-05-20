-- M7 (Suite promotion): track the follow-up PR opened by SuitePromotionService
-- so a re-run of the same workflow run never opens duplicate promotion PRs
-- (idempotency guard) and operators can navigate from the original run to
-- the resulting follow-up PR in the dashboard.

ALTER TABLE pr_workflow_runs
    ADD COLUMN IF NOT EXISTS follow_up_pr_number BIGINT;
