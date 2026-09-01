-- V48: Track SSH keys registered automatically through the Gitea API.
ALTER TABLE git_integrations ADD COLUMN ssh_remote_key_id BIGINT;
