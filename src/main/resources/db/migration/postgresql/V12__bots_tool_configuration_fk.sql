-- Step 2 of the bot tool configuration rollout (see V11 for the tables):
-- attach every bot to exactly one tool configuration, defaulting existing
-- bots to the auto-generated "Default" configuration so that the new
-- mandatory association does not break upgrades.

ALTER TABLE bots ADD COLUMN IF NOT EXISTS bot_tool_configuration_id BIGINT;

INSERT INTO bot_tool_configurations (name, default_entry, created_at, updated_at)
SELECT 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM bot_tool_configurations WHERE default_entry = TRUE
);

UPDATE bots
SET bot_tool_configuration_id = (
    SELECT id FROM bot_tool_configurations WHERE default_entry = TRUE LIMIT 1
)
WHERE bot_tool_configuration_id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_bots_tool_configuration'
          AND table_name = 'bots'
    ) THEN
        ALTER TABLE bots
            ADD CONSTRAINT fk_bots_tool_configuration
            FOREIGN KEY (bot_tool_configuration_id) REFERENCES bot_tool_configurations(id);
    END IF;
END $$;

ALTER TABLE bots ALTER COLUMN bot_tool_configuration_id SET NOT NULL;
