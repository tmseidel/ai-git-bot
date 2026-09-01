-- V50: Persist the unique title used to recover an automatically registered SSH key.
ALTER TABLE git_integrations ADD COLUMN ssh_remote_key_title VARCHAR(255);
