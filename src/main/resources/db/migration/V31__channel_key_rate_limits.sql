create table channel_key_rate_limits (
    key_id varchar(36) primary key references channel_keys (id) on delete cascade,
    enabled boolean not null default false,
    requests_per_minute integer,
    max_concurrent_requests integer,
    updated_at bigint not null,
    constraint ck_channel_key_rate_limit_requests
        check (requests_per_minute is null or requests_per_minute between 1 and 1000000),
    constraint ck_channel_key_rate_limit_concurrency
        check (max_concurrent_requests is null or max_concurrent_requests between 1 and 100000),
    constraint ck_channel_key_rate_limit_enabled_value
        check (not enabled or requests_per_minute is not null or max_concurrent_requests is not null)
);
