-- 管理台可编辑的 IQ 视觉判分配置。没有行时沿用环境变量默认值，
-- 因此升级后未保存过设置的部署行为不变。
--
-- 只保存可以在运行时热切换的字段：启用开关、OpenAI 兼容端点、模型名、调用密钥和视觉超时。
-- 渲染宽度、单帧浏览器超时和浏览器路径属于进程级资源绑定，仍由部署环境变量决定。
create table iq_vision_settings (
    id integer primary key,
    enabled boolean not null,
    base_url varchar(500) not null default '',
    model varchar(200) not null default '',
    api_key_ciphertext text not null default '',
    api_key_nonce text not null default '',
    api_key_key_version integer not null default 1,
    vision_timeout_ms bigint not null,
    updated_at bigint not null,
    constraint iq_vision_settings_singleton check (id = 1)
);
