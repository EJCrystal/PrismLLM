-- 规则及失败计数持久化；密钥停用与对应审计在同一事务提交。
create table key_auto_disable_settings (
    id integer primary key check (id = 1),
    enabled boolean not null,
    auth_failure_threshold integer not null check (auth_failure_threshold between 1 and 100),
    failure_threshold integer not null check (failure_threshold between 1 and 1000),
    failure_window_minutes integer not null check (failure_window_minutes between 1 and 1440),
    revision bigint not null default 0,
    updated_at bigint not null
);

insert into key_auto_disable_settings
    (id, enabled, auth_failure_threshold, failure_threshold, failure_window_minutes, revision, updated_at)
values (1, true, 3, 5, 5, 0, (extract(epoch from now()) * 1000)::bigint);

alter table channel_keys
    add column state_version bigint not null default 0,
    add column auto_disabled_at bigint,
    add column auto_disable_reason varchar(40);

create table channel_key_health (
    key_id varchar(36) primary key references channel_keys (id) on delete cascade,
    policy_revision bigint not null,
    auth_failure_streak integer not null default 0,
    recent_failures text not null default '[]'
);

-- 仅写入由网关生成的规则、计数和渠道/密钥名称等元数据，禁止保存请求正文或凭据。
alter table admin_audit_logs add column details text;
