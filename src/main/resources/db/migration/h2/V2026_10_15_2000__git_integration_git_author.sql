-- V60: Add the Git author identity used for commits that bots of a Git integration
-- push (issue agent, README sync, i18n coverage, unit tests, E2E suites).
-- Existing integrations get the previous built-in identity "AI Agent <ai-agent@bot.local>".
ALTER TABLE git_integrations ADD COLUMN IF NOT EXISTS git_author_name VARCHAR(255) NOT NULL DEFAULT 'AI Agent';
ALTER TABLE git_integrations ADD COLUMN IF NOT EXISTS git_author_email VARCHAR(255) NOT NULL DEFAULT 'ai-agent@bot.local';
