-- Optional per-bot webhook signing secret used to verify the provider's
-- webhook signature (X-Hub-Signature-256 / X-Gitea-Signature / X-Gitlab-Token)
-- in addition to the URL path secret. Stored AES-GCM-encrypted by the app.

ALTER TABLE bots ADD COLUMN webhook_signing_secret VARCHAR(1000);