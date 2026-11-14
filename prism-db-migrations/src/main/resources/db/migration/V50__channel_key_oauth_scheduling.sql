-- 订阅账号级调度：账号级滑动窗口、单号并发上限与 429 冷却。
-- 窗口按账号统计（不区分模型），与上游订阅配额的作用域一致。
alter table channel_key_oauth_accounts
    add column window_limit integer,
    add column window_seconds integer not null default 10800,
    add column max_concurrent_requests integer not null default 1;

alter table channel_key_oauth_accounts
    add constraint ck_channel_key_oauth_window_limit
        check (window_limit is null or window_limit between 1 and 100000),
    add constraint ck_channel_key_oauth_window_seconds
        check (window_seconds between 60 and 604800),
    add constraint ck_channel_key_oauth_max_concurrent
        check (max_concurrent_requests between 1 and 1000);

-- 账号级调度状态快照。内存态是权威来源，这张表只用于重启恢复和看板展示。
create table channel_key_oauth_windows (
    key_id varchar(36) primary key
        references channel_key_oauth_accounts (key_id) on delete cascade,
    -- 窗口内每次准入的时间戳（epoch millis），JSON 数组，按时间升序。
    hits text not null default '[]',
    cooldown_until bigint,
    consecutive_rate_limits integer not null default 0,
    updated_at bigint not null,
    constraint ck_channel_key_oauth_windows_rate_limits
        check (consecutive_rate_limits >= 0)
);
