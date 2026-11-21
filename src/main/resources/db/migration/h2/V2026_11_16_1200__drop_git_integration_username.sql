-- The bot's account is resolved from the Git provider with the integration's token.
ALTER TABLE git_integrations DROP COLUMN IF EXISTS username;
