-- 订阅账号从 channel_keys 中彻底解耦：账号拥有独立 id、凭据、策略与生命周期，
-- 渠道通过 oauth_account_channels 与账号建立多对多关联。
--
-- 迁移策略：旧 OAuth 密钥的 key_id 直接作为新账号 id，refresh token 从
-- channel_keys.credential_* 搬到 oauth_accounts.refresh_token_*；迁移完成后删除
-- 这些仅供 OAuth 使用的密钥，避免同一账号在数据面出现两次候选。

create table oauth_accounts (
    id varchar(36) primary key,
    name varchar(100) not null,
    provider varchar(32) not null,
    account_email varchar(320),
    account_plan varchar(32),
    refresh_token_ciphertext text not null,
    refresh_token_nonce text not null,
    refresh_token_key_version integer not null,
    access_token_ciphertext text,
    access_token_nonce text,
    access_token_key_version integer,
    access_token_expires_at bigint,
    token_type varchar(32) not null default 'Bearer',
    scope text,
    auth_state varchar(20) not null default 'ACTIVE',
    enabled boolean not null default true,
    priority integer not null default 1,
    refresh_failure_count integer not null default 0,
    last_refresh_error varchar(200),
    last_refreshed_at bigint,
    proxy_url varchar(500),
    window_limit integer,
    window_seconds integer not null default 10800,
    max_concurrent_requests integer not null default 1,
    chatgpt_account_id varchar(128),
    quota_primary_used_percent double precision,
    quota_primary_window_seconds integer,
    quota_primary_resets_at bigint,
    quota_secondary_used_percent double precision,
    quota_secondary_window_seconds integer,
    quota_secondary_resets_at bigint,
    quota_allowed boolean,
    quota_credits_balance double precision,
    quota_credits_unlimited boolean not null default false,
    quota_reset_credits integer,
    quota_checked_at bigint,
    quota_error varchar(200),
    created_at bigint not null,
    updated_at bigint not null,
    constraint ck_oauth_accounts_provider check (provider in ('chatgpt', 'antigravity')),
    constraint ck_oauth_accounts_auth_state check (auth_state in ('ACTIVE', 'REAUTH_REQUIRED')),
    constraint ck_oauth_accounts_failure_count check (refresh_failure_count >= 0),
    constraint ck_oauth_accounts_priority check (priority between 1 and 1000),
    constraint ck_oauth_accounts_window_limit
        check (window_limit is null or window_limit between 1 and 100000),
    constraint ck_oauth_accounts_window_seconds check (window_seconds between 60 and 604800),
    constraint ck_oauth_accounts_max_concurrent check (max_concurrent_requests between 1 and 1000),
    constraint ck_oauth_accounts_quota_primary
        check (quota_primary_used_percent is null or quota_primary_used_percent between 0 and 100),
    constraint ck_oauth_accounts_quota_secondary
        check (quota_secondary_used_percent is null or quota_secondary_used_percent between 0 and 100),
    constraint ck_oauth_accounts_quota_reset_credits
        check (quota_reset_credits is null or quota_reset_credits >= 0),
    constraint ck_oauth_accounts_quota_window_seconds
        check ((quota_primary_window_seconds is null or quota_primary_window_seconds > 0)
            and (quota_secondary_window_seconds is null or quota_secondary_window_seconds > 0)),
    constraint ck_oauth_accounts_access_token check (
        (access_token_ciphertext is null and access_token_nonce is null
            and access_token_key_version is null and access_token_expires_at is null)
        or (access_token_ciphertext is not null and access_token_nonce is not null
            and access_token_key_version is not null and access_token_expires_at is not null)
    )
);

create unique index uk_oauth_accounts_email
    on oauth_accounts (provider, lower(account_email))
    where account_email is not null;

create index idx_oauth_accounts_refresh_due
    on oauth_accounts (access_token_expires_at)
    where auth_state = 'ACTIVE' and enabled;

create index idx_oauth_accounts_quota_checked
    on oauth_accounts (quota_checked_at)
    where auth_state = 'ACTIVE' and enabled;

create table oauth_account_channels (
    account_id varchar(36) not null references oauth_accounts (id) on delete cascade,
    channel_id varchar(36) not null references channels (id) on delete cascade,
    created_at bigint not null,
    primary key (account_id, channel_id)
);

create index idx_oauth_account_channels_channel
    on oauth_account_channels (channel_id);

create table oauth_account_models (
    account_id varchar(36) not null references oauth_accounts (id) on delete cascade,
    upstream_model varchar(200) not null,
    created_at bigint not null,
    primary key (account_id, upstream_model)
);

create table oauth_account_windows (
    account_id varchar(36) primary key references oauth_accounts (id) on delete cascade,
    hits text not null default '[]',
    cooldown_until bigint,
    consecutive_rate_limits integer not null default 0,
    updated_at bigint not null,
    constraint ck_oauth_account_windows_rate_limits check (consecutive_rate_limits >= 0)
);

-- 旧 OAuth 密钥在 channel_keys 中已经是独立凭据，直接把 key_id 复用为账号 id。
insert into oauth_accounts (
    id, name, provider, account_email, account_plan,
    refresh_token_ciphertext, refresh_token_nonce, refresh_token_key_version,
    access_token_ciphertext, access_token_nonce, access_token_key_version, access_token_expires_at,
    token_type, scope, auth_state, enabled, priority,
    refresh_failure_count, last_refresh_error, last_refreshed_at, proxy_url,
    window_limit, window_seconds, max_concurrent_requests, chatgpt_account_id,
    quota_primary_used_percent, quota_primary_window_seconds, quota_primary_resets_at,
    quota_secondary_used_percent, quota_secondary_window_seconds, quota_secondary_resets_at,
    quota_allowed, quota_credits_balance, quota_credits_unlimited, quota_reset_credits,
    quota_checked_at, quota_error, created_at, updated_at
)
select
    a.key_id,
    left(coalesce(nullif(a.account_email, ''), k.name), 100),
    a.provider,
    a.account_email,
    a.account_plan,
    k.credential_ciphertext,
    k.credential_nonce,
    k.credential_key_version,
    a.access_token_ciphertext,
    a.access_token_nonce,
    a.access_token_key_version,
    a.access_token_expires_at,
    a.token_type,
    a.scope,
    a.auth_state,
    k.state = 'ACTIVE',
    least(greatest(k.priority, 1), 1000),
    a.refresh_failure_count,
    a.last_refresh_error,
    a.last_refreshed_at,
    a.proxy_url,
    a.window_limit,
    a.window_seconds,
    a.max_concurrent_requests,
    a.chatgpt_account_id,
    a.quota_primary_used_percent,
    a.quota_primary_window_seconds,
    a.quota_primary_resets_at,
    a.quota_secondary_used_percent,
    a.quota_secondary_window_seconds,
    a.quota_secondary_resets_at,
    a.quota_allowed,
    a.quota_credits_balance,
    a.quota_credits_unlimited,
    a.quota_reset_credits,
    a.quota_checked_at,
    a.quota_error,
    k.created_at,
    k.updated_at
from channel_key_oauth_accounts a
join channel_keys k on k.id = a.key_id;

insert into oauth_account_channels (account_id, channel_id, created_at)
select a.key_id, k.channel_id, k.created_at
from channel_key_oauth_accounts a
join channel_keys k on k.id = a.key_id;

insert into oauth_account_models (account_id, upstream_model, created_at)
select m.key_id, m.upstream_model, m.created_at
from channel_key_models m
join channel_key_oauth_accounts a on a.key_id = m.key_id;

insert into oauth_account_windows (account_id, hits, cooldown_until, consecutive_rate_limits, updated_at)
select w.key_id, w.hits, w.cooldown_until, w.consecutive_rate_limits, w.updated_at
from channel_key_oauth_windows w;

-- 这些密钥只用于承载 OAuth 账号；账号迁出后删除，避免同一账号产生重复候选。
delete from channel_keys
where id in (select key_id from channel_key_oauth_accounts);

drop table channel_key_oauth_windows;
drop table channel_key_oauth_accounts;
