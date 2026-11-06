-- 订阅账号的上游额度快照。ChatGPT 的 /wham/usage 会返回 5 小时与每周两个窗口的
-- 使用比例和重置时间，比本地滑动窗口更接近上游真实配额；这里只保存最近一次读数，
-- 不参与凭据加密，也不保存任何 token。
alter table channel_key_oauth_accounts
    add column chatgpt_account_id varchar(128),
    add column quota_primary_used_percent double precision,
    add column quota_primary_window_seconds integer,
    add column quota_primary_resets_at bigint,
    add column quota_secondary_used_percent double precision,
    add column quota_secondary_window_seconds integer,
    add column quota_secondary_resets_at bigint,
    -- rate_limit.allowed：上游明确表示该账号当前是否还能继续请求。
    add column quota_allowed boolean,
    add column quota_credits_balance double precision,
    add column quota_credits_unlimited boolean not null default false,
    -- rate_limit_reset_credits.available_count：可主动重置窗口的额度次数。
    add column quota_reset_credits integer,
    add column quota_checked_at bigint,
    add column quota_error varchar(200);

alter table channel_key_oauth_accounts
    add constraint ck_channel_key_oauth_quota_primary
        check (quota_primary_used_percent is null or quota_primary_used_percent between 0 and 100),
    add constraint ck_channel_key_oauth_quota_secondary
        check (quota_secondary_used_percent is null or quota_secondary_used_percent between 0 and 100),
    add constraint ck_channel_key_oauth_quota_reset_credits
        check (quota_reset_credits is null or quota_reset_credits >= 0),
    add constraint ck_channel_key_oauth_quota_window_seconds
        check ((quota_primary_window_seconds is null or quota_primary_window_seconds > 0)
            and (quota_secondary_window_seconds is null or quota_secondary_window_seconds > 0));

-- 后台额度扫描按最近读取时间排序，只关心仍可自动刷新的账号。
create index idx_channel_key_oauth_quota_checked
    on channel_key_oauth_accounts (quota_checked_at)
    where auth_state = 'ACTIVE';
