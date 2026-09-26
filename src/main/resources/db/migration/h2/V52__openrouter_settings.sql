ALTER TABLE ai_integrations ADD COLUMN openrouter_region VARCHAR(16) DEFAULT 'GLOBAL' NOT NULL;
ALTER TABLE ai_integrations ADD COLUMN openrouter_data_collection VARCHAR(16) DEFAULT 'DENY' NOT NULL;
ALTER TABLE ai_integrations ADD COLUMN openrouter_zdr BOOLEAN DEFAULT FALSE NOT NULL;
