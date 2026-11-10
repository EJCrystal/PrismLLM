-- access token 是短期凭据（通常约 1 小时），到期前会自动用 refresh token 续期，
-- 不能代表账号授权的有效期。单独的 authorized_at 记录 refresh token 的获取时间：
-- 首次导入/浏览器登录以及管理员重新授权时更新，日常 token 刷新与轮换不改变。
alter table oauth_accounts
    add column authorized_at bigint;

-- 已有账号没有历史授权时间，用创建时间回填；后续重新授权会刷新该值。
update oauth_accounts
set authorized_at = created_at
where authorized_at is null;

alter table oauth_accounts
    alter column authorized_at set not null;