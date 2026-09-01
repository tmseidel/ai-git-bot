-- V51: Coordinate concurrent Git integration edits, setup, assignment, and deletion.
ALTER TABLE git_integrations ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE git_integrations ADD COLUMN deletion_pending BOOLEAN NOT NULL DEFAULT FALSE;
