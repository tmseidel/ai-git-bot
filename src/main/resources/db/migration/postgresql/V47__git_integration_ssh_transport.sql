-- V47: Configure repository Git transport and SSH credentials per integration.
ALTER TABLE git_integrations ADD COLUMN transport VARCHAR(16) NOT NULL DEFAULT 'HTTP';
ALTER TABLE git_integrations ADD COLUMN ssh_private_key TEXT;
ALTER TABLE git_integrations ADD COLUMN ssh_known_hosts TEXT;
