-- 团队限流移除：调用方侧分组限流从未使用（全部署无团队标签），
-- 真实需求是上游侧渠道密钥级限流，随多 key 渠道另行设计。
drop table if exists team_limits;

alter table client_keys
    drop column if exists team;

alter table request_logs
    drop column if exists team;
