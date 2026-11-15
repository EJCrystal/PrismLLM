-- 保留 V68 的不限制与白名单语义，黑名单拥有独立模式和关联。
alter table client_keys
    add column access_channels_denied boolean not null default false,
    add column access_channel_keys_denied boolean not null default false,
    add column access_models_denied boolean not null default false,
    add constraint ck_client_key_channels_mode check (not (access_channels_restricted and access_channels_denied)),
    add constraint ck_client_key_channel_keys_mode check (not (access_channel_keys_restricted and access_channel_keys_denied)),
    add constraint ck_client_key_models_mode check (not (access_models_restricted and access_models_denied));

create table client_key_denied_channels (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    channel_id varchar(36) not null references channels(id) on delete cascade,
    primary key (client_key_id, channel_id)
);
create index idx_client_key_denied_channels_target on client_key_denied_channels(channel_id);

create table client_key_denied_channel_keys (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    channel_id varchar(36) not null,
    key_id varchar(36) not null,
    primary key (client_key_id, channel_id, key_id),
    foreign key (channel_id, key_id) references channel_keys(channel_id, id) on delete cascade
);
create index idx_client_key_denied_channel_keys_target on client_key_denied_channel_keys(channel_id, key_id);

create table client_key_denied_models (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    model_id varchar(200) not null,
    primary key (client_key_id, model_id)
);
