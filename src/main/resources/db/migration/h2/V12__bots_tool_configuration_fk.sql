-- Step 2 of the bot tool configuration rollout (see V11 for the tables):
-- attach every bot to exactly one tool configuration, defaulting existing
-- bots to the auto-generated "Default" configuration so that the new
-- mandatory association does not break upgrades.
--
-- Sequence (idempotent, order matters):
--   1) add the FK column to bots, nullable for backfill
--   2) ensure a default tool configuration exists (the Java initializer
--      DefaultBotToolConfigurationInitializer fills it with built-in tool
--      selections at application startup — SQL only guarantees the row)
--   3) backfill bots.bot_tool_configuration_id to the default configuration
--   4) add the foreign key constraint
--   5) enforce NOT NULL once every row is backfilled

ALTER TABLE bots ADD COLUMN IF NOT EXISTS bot_tool_configuration_id BIGINT;

INSERT INTO bot_tool_configurations (name, default_entry, created_at, updated_at)
SELECT 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM bot_tool_configurations WHERE default_entry = TRUE
);

UPDATE bots
SET bot_tool_configuration_id = (
    SELECT id FROM bot_tool_configurations WHERE default_entry = TRUE FETCH FIRST 1 ROWS ONLY
)
WHERE bot_tool_configuration_id IS NULL;

ALTER TABLE bots
    ADD CONSTRAINT IF NOT EXISTS fk_bots_tool_configuration
    FOREIGN KEY (bot_tool_configuration_id) REFERENCES bot_tool_configurations(id);

ALTER TABLE bots ALTER COLUMN bot_tool_configuration_id BIGINT NOT NULL;
