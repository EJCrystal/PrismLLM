alter table client_keys
    add column daily_token_limit bigint,
    add column daily_usd_limit numeric(18, 8),
    add column daily_cny_limit numeric(18, 8),
    add column daily_eur_limit numeric(18, 8),
    add column max_concurrent_requests integer;

alter table client_keys
    add constraint client_keys_daily_token_limit_non_negative check (daily_token_limit is null or daily_token_limit >= 0),
    add constraint client_keys_daily_usd_limit_non_negative check (daily_usd_limit is null or daily_usd_limit >= 0),
    add constraint client_keys_daily_cny_limit_non_negative check (daily_cny_limit is null or daily_cny_limit >= 0),
    add constraint client_keys_daily_eur_limit_non_negative check (daily_eur_limit is null or daily_eur_limit >= 0),
    add constraint client_keys_max_concurrent_requests_positive check (max_concurrent_requests is null or max_concurrent_requests > 0);

create table client_key_quota_usage (
    client_key_id varchar(36) not null references client_keys (id) on delete cascade,
    period bigint not null,
    tokens bigint not null default 0,
    usd_cost numeric(18, 8) not null default 0,
    cny_cost numeric(18, 8) not null default 0,
    eur_cost numeric(18, 8) not null default 0,
    primary key (client_key_id, period)
);
