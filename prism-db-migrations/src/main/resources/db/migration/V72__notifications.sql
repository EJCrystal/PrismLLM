-- Existing per-target event snapshots are immutable; no target foreign key is used so delivery history survives deletion.
CREATE TABLE notification_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    consecutive_failure_threshold INTEGER NOT NULL DEFAULT 5 CHECK (consecutive_failure_threshold BETWEEN 1 AND 100),
    updated_at BIGINT NOT NULL DEFAULT 0
);

INSERT INTO notification_settings (id, enabled, consecutive_failure_threshold, updated_at)
VALUES (1, FALSE, 5, 0);

CREATE TABLE notification_targets (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    events TEXT NOT NULL,
    channel_ids TEXT NOT NULL,
    environment_prefix VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at BIGINT NOT NULL
);

CREATE INDEX notification_targets_enabled_idx ON notification_targets (enabled);

CREATE TABLE notification_alert_states (
    subject VARCHAR(200) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    state VARCHAR(100),
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (subject, kind)
);

CREATE INDEX notification_alert_states_cleanup_idx
    ON notification_alert_states (kind, active, updated_at);

CREATE TABLE notification_events (
    event_id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX notification_events_created_idx ON notification_events (created_at);

CREATE TABLE notification_deliveries (
    id VARCHAR(36) PRIMARY KEY,
    queue_order BIGSERIAL NOT NULL UNIQUE,
    event_id VARCHAR(36) NOT NULL REFERENCES notification_events (event_id),
    target_id VARCHAR(36) NOT NULL,
    target_name VARCHAR(100) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    event_type VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('pending', 'in_flight', 'succeeded', 'failed', 'cancelled')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    next_attempt_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_status_code INTEGER,
    error_code VARCHAR(100),
    lease_token VARCHAR(36),
    lease_until BIGINT,
    CONSTRAINT notification_deliveries_event_target_unique UNIQUE (event_id, target_id),
    CONSTRAINT notification_deliveries_lease_consistency CHECK (
        (status = 'in_flight' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'in_flight' AND lease_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX notification_deliveries_due_idx
    ON notification_deliveries (status, next_attempt_at, created_at);
CREATE INDEX notification_deliveries_target_pending_order_idx
    ON notification_deliveries (target_id, queue_order)
    WHERE status IN ('pending', 'in_flight');
CREATE INDEX notification_deliveries_target_history_idx
    ON notification_deliveries (target_id, created_at DESC);
CREATE INDEX notification_deliveries_history_idx
    ON notification_deliveries (created_at DESC);
