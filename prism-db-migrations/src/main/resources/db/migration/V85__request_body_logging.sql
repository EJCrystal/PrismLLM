ALTER TABLE retention_settings
    ADD COLUMN request_body_logging_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE request_logs
    ADD COLUMN request_body_size_bytes BIGINT NULL,
    ADD COLUMN request_body_size_exact BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN request_body_captured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN request_body_truncated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE request_log_bodies (
    log_id BIGINT PRIMARY KEY REFERENCES request_logs(id) ON DELETE CASCADE,
    body_base64 TEXT NOT NULL
);
