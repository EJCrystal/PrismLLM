-- 管理台可编辑的渠道出口代理主机白名单。主机列表以 JSON 数组保存；
-- 没有行时只使用 CHANNEL_PROXY_ALLOWED_HOSTS 提供的部署默认值。
create table channel_egress_settings (
    id integer primary key,
    allowed_hosts text not null default '[]',
    revision bigint not null default 0,
    updated_at bigint not null,
    constraint channel_egress_settings_singleton check (id = 1)
);
