-- V49: Track the immutable Gitea user that owns an automatically registered SSH key.
ALTER TABLE git_integrations ADD COLUMN ssh_remote_key_owner_id BIGINT;
