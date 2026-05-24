-- Per-bot user whitelist (Issue: protect token spend on public repositories).
ALTER TABLE bots ADD COLUMN IF NOT EXISTS user_whitelist TEXT;

