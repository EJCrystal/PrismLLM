-- 旧调用方密钥保持三个维度均不限制；空白名单由独立模式标记表达。
alter table client_keys
    add column access_channels_restricted boolean not null default false,
    add column access_channel_keys_restricted boolean not null default false,
    add column access_models_restricted boolean not null default false;

-- 复合外键确保静态密钥与其渠道不能被错误配对。
alter table channel_keys
    add constraint uk_channel_keys_channel_id_id unique (channel_id, id);

create table client_key_allowed_channels (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    channel_id varchar(36) not null references channels(id) on delete cascade,
    primary key (client_key_id, channel_id)
);
create index idx_client_key_allowed_channels_target on client_key_allowed_channels(channel_id);

create table client_key_allowed_channel_keys (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    channel_id varchar(36) not null,
    key_id varchar(36) not null,
    primary key (client_key_id, channel_id, key_id),
    foreign key (channel_id, key_id) references channel_keys(channel_id, id) on delete cascade
);
create index idx_client_key_allowed_channel_keys_target on client_key_allowed_channel_keys(channel_id, key_id);

create table client_key_allowed_models (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    model_id varchar(200) not null,
    primary key (client_key_id, model_id)
);
