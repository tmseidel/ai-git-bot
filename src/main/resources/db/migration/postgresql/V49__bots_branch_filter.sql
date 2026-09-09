-- V47: Add the per-bot branch/ref allowlist for PR-workflow triggering.
-- The filter is a comma-separated list of gobwas/glob-style patterns.
-- Empty (the default) or '*' allows every branch/tag, so existing bots keep
-- their current behaviour.
ALTER TABLE bots ADD COLUMN IF NOT EXISTS branch_filter VARCHAR(1000) NOT NULL DEFAULT '';
