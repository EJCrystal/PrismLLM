-- 通知目标凭据改为管理台录入并以 AES-256-GCM 密文存储，不再引用环境变量前缀。
CREATE TABLE notification_target_credentials (
    target_id VARCHAR(36) PRIMARY KEY
        REFERENCES notification_targets (id) ON DELETE CASCADE,
    url_ciphertext TEXT NOT NULL DEFAULT '',
    url_nonce TEXT NOT NULL DEFAULT '',
    url_key_version INTEGER NOT NULL DEFAULT 1,
    bearer_token_ciphertext TEXT NOT NULL DEFAULT '',
    bearer_token_nonce TEXT NOT NULL DEFAULT '',
    bearer_token_key_version INTEGER NOT NULL DEFAULT 1,
    signing_secret_ciphertext TEXT NOT NULL DEFAULT '',
    signing_secret_nonce TEXT NOT NULL DEFAULT '',
    signing_secret_key_version INTEGER NOT NULL DEFAULT 1,
    updated_at BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE notification_targets DROP COLUMN environment_prefix;
