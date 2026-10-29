-- The bot's username is now resolved from the Git provider with the integration's token.
ALTER TABLE git_integrations DROP COLUMN username;
