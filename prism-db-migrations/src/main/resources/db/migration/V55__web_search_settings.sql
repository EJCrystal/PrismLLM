-- 管理台可编辑的 Web 搜索设置。没有行时沿用环境变量默认值，
-- 因此升级后未保存过设置的部署行为不变。
create table web_search_settings (
    id integer primary key,
    enabled boolean not null,
    provider varchar(20) not null,
    api_key_ciphertext text not null default '',
    api_key_nonce text not null default '',
    api_key_key_version integer not null default 1,
    base_url varchar(500),
    max_results integer not null,
    timeout_ms bigint not null,
    max_rounds integer not null,
    max_page_chars integer not null,
    updated_at bigint not null,
    constraint web_search_settings_singleton check (id = 1)
);
