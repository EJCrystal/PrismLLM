ALTER TABLE channels ADD COLUMN egress_proxies TEXT NOT NULL DEFAULT '[]';
ALTER TABLE channel_keys ADD COLUMN egress_proxy_id VARCHAR(36);
