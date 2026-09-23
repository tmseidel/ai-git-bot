ALTER TABLE git_integrations ADD COLUMN IF NOT EXISTS ssh_remote_key_id BIGINT;
ALTER TABLE git_integrations ADD COLUMN IF NOT EXISTS ssh_remote_key_owner_id BIGINT;
ALTER TABLE git_integrations ADD COLUMN IF NOT EXISTS ssh_remote_key_title VARCHAR(255);
