-- 额度扫描排队：失败也必须推进“最近一次尝试”，否则持续失败的账号会一直排在队首，
-- 账号数超过一轮扫描上限（100）时会把健康账号的额度刷新饿死。
-- 成功读数的新鲜度仍由 quota_checked_at 表达，两列语义分开。
alter table oauth_accounts
    add column quota_attempted_at bigint;

-- 迁移前只按成功读数排队；用成功读数时间作初值，保持原有刷新节奏。
update oauth_accounts
set quota_attempted_at = quota_checked_at
where quota_checked_at is not null;

create index idx_oauth_accounts_quota_attempted
    on oauth_accounts (quota_attempted_at)
    where auth_state = 'ACTIVE' and enabled;
