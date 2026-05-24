-- Per-bot user whitelist (H2 mirror of the postgresql/V21 migration).
-- See the postgresql variant for the full rationale.
ALTER TABLE bots ADD COLUMN IF NOT EXISTS user_whitelist TEXT;

