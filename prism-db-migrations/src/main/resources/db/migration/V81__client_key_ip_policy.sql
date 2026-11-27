ALTER TABLE client_keys
    ADD COLUMN ip_allowlist TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN ip_denylist TEXT NOT NULL DEFAULT '[]';
