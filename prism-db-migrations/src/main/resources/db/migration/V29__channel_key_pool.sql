-- 渠道密钥池：一个渠道可以挂多个上游密钥，每个密钥有自己的优先级和模型集合。
create table channel_keys (
    id varchar(36) primary key,
    channel_id varchar(36) not null references channels (id) on delete cascade,
    name varchar(100) not null,
    credential_ciphertext text not null,
    credential_nonce text not null,
    credential_key_version integer not null,
    enabled boolean not null default true,
    priority integer not null default 1,
    created_at bigint not null,
    updated_at bigint not null,
    constraint uk_channel_keys_name unique (channel_id, name)
);

create index idx_channel_keys_channel_priority on channel_keys (channel_id, priority);

create table channel_key_models (
    key_id varchar(36) not null references channel_keys (id) on delete cascade,
    upstream_model varchar(200) not null,
    created_at bigint not null,
    primary key (key_id, upstream_model)
);

-- 现有渠道凭据迁移为“默认密钥”，并继承渠道当前授权的模型。
insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce, credential_key_version, enabled, priority, created_at, updated_at)
select
    md5(random()::text || clock_timestamp()::text)::uuid::text,
    c.id,
    '默认密钥',
    c.credential_ciphertext,
    c.credential_nonce,
    c.credential_key_version,
    true,
    1,
    c.created_at,
    c.updated_at
from channels c;

insert into channel_key_models (key_id, upstream_model, created_at)
select k.id, m.upstream_model, m.created_at
from channel_keys k
join channel_models m on m.channel_id = k.channel_id;

alter table request_logs
    add column channel_key_id varchar(36);

alter table request_logs
    add column channel_key_name varchar(100);

alter table channels
    drop column credential_ciphertext,
    drop column credential_nonce,
    drop column credential_key_version;
