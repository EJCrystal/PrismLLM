CREATE TABLE channel_key_quota_settings (
    key_id VARCHAR(36) PRIMARY KEY REFERENCES channel_keys(id) ON DELETE CASCADE,
    settings TEXT NOT NULL DEFAULT '{}',
    credential_ciphertext TEXT NOT NULL DEFAULT '',
    credential_nonce TEXT NOT NULL DEFAULT '',
    credential_key_version INTEGER NOT NULL DEFAULT 1,
    updated_at BIGINT NOT NULL
);
