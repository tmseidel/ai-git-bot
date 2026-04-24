-- Increase api_key length to 1000 characters to accommodate longer encrypted tokens
ALTER TABLE ai_integrations ALTER COLUMN api_key TYPE VARCHAR(1000);

