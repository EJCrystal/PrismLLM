-- Keep attempt history with its request; retention cleanup cascades in batches.
alter table request_logs add column health_indexed boolean not null default false;
create index idx_request_logs_health_pending on request_logs (id) where not health_indexed;
create index idx_request_logs_channel_created on request_logs (channel_id, created_at);

create table request_attempts (
    log_id bigint not null references request_logs(id) on delete cascade,
    attempt_index integer not null,
    channel_id varchar(36) not null,
    channel_key_id varchar(36),
    upstream_model varchar(200),
    upstream_protocol varchar(40),
    stream boolean,
    outcome varchar(12) not null,
    error_code text,
    status_code integer,
    first_token_ms bigint,
    elapsed_ms bigint not null,
    output_tokens bigint,
    created_at bigint not null,
    primary key (log_id, attempt_index)
);
create index idx_request_attempts_channel_created on request_attempts (channel_id, created_at);
create index idx_request_attempts_key_created on request_attempts (channel_key_id, created_at);
create index idx_request_attempts_created on request_attempts (created_at);
-- Historical JSON is indexed in small background batches, never in this migration.
