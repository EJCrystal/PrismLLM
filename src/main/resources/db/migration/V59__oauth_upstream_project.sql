-- Antigravity 走 Google Code Assist：每个账号需要自己的 cloud project 与 tier，
-- 由 loadCodeAssist / onboardUser 发现并缓存，避免每个请求都重新协商。
alter table oauth_accounts
    add column upstream_project varchar(128),
    add column upstream_tier varchar(64),
    add column upstream_tier_checked_at bigint;
