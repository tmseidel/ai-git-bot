ALTER TABLE git_integrations
    ADD COLUMN IF NOT EXISTS post_review_action VARCHAR(32) NOT NULL DEFAULT 'NONE';
