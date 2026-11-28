ALTER TABLE antigravity_agy_settings ADD COLUMN proxy_username TEXT;
ALTER TABLE antigravity_agy_settings ADD COLUMN proxy_password_ciphertext TEXT NOT NULL DEFAULT '';
ALTER TABLE antigravity_agy_settings ADD COLUMN proxy_password_nonce TEXT NOT NULL DEFAULT '';
ALTER TABLE antigravity_agy_settings ADD COLUMN proxy_password_key_version INTEGER NOT NULL DEFAULT 1;
