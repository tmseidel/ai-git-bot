ALTER TABLE ai_integrations ADD COLUMN IF NOT EXISTS model_flavor VARCHAR(100) NOT NULL DEFAULT 'standard';
