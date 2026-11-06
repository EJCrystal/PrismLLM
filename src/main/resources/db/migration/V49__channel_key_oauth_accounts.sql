-- 订阅账号 OAuth 状态：与 channel_keys 一对一。
-- 长期 refresh token 继续存放在 channel_keys 的加密凭据列（credential_*），
-- 复用既有的凭据加密、密钥启停、优先级、模型授权和冷却机制。
-- 本表只保存短期 access token 缓存、刷新诊断与账号级出口代理。
create table channel_key_oauth_accounts (
    key_id varchar(36) primary key references channel_keys (id) on delete cascade,
    provider varchar(32) not null,
    account_email varchar(320),
    account_plan varchar(32),
    access_token_ciphertext text,
    access_token_nonce text,
    access_token_key_version integer,
    access_token_expires_at bigint,
    token_type varchar(32) not null default 'Bearer',
    scope text,
    -- ACTIVE：可自动刷新；REAUTH_REQUIRED：refresh token 已失效，等待管理员重新导入。
    auth_state varchar(20) not null default 'ACTIVE',
    refresh_failure_count integer not null default 0,
    last_refresh_error varchar(200),
    last_refreshed_at bigint,
    proxy_url varchar(500),
    created_at bigint not null,
    updated_at bigint not null,
    constraint ck_channel_key_oauth_provider check (provider in ('chatgpt', 'antigravity')),
    constraint ck_channel_key_oauth_auth_state check (auth_state in ('ACTIVE', 'REAUTH_REQUIRED')),
    constraint ck_channel_key_oauth_failure_count check (refresh_failure_count >= 0),
    -- access token 的密文、nonce、密钥版本和到期时间必须同时存在或同时为空。
    constraint ck_channel_key_oauth_access_token check (
        (access_token_ciphertext is null and access_token_nonce is null
            and access_token_key_version is null and access_token_expires_at is null)
        or (access_token_ciphertext is not null and access_token_nonce is not null
            and access_token_key_version is not null and access_token_expires_at is not null)
    )
);

-- 后台刷新扫描按到期时间排序，只关心仍可自动刷新的账号。
create index idx_channel_key_oauth_refresh_due
    on channel_key_oauth_accounts (access_token_expires_at)
    where auth_state = 'ACTIVE';

-- 同一 provider 下同一邮箱只允许绑定一个密钥，避免同一账号被拆成多个密钥重复计入滑动窗口。
create unique index uk_channel_key_oauth_account_email
    on channel_key_oauth_accounts (provider, lower(account_email))
    where account_email is not null;
