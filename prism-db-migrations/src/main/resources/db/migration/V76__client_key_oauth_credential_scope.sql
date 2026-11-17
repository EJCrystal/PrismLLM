-- 调用方访问范围的凭据维度支持订阅账号身份；静态密钥继续使用原有表与外键。
create table client_key_allowed_oauth_accounts (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    account_id varchar(36) not null,
    channel_id varchar(36) not null,
    primary key (client_key_id, account_id, channel_id),
    foreign key (account_id, channel_id) references oauth_account_channels(account_id, channel_id) on delete cascade
);
create index idx_client_key_allowed_oauth_accounts_target
    on client_key_allowed_oauth_accounts(channel_id, account_id);

create table client_key_denied_oauth_accounts (
    client_key_id varchar(36) not null references client_keys(id) on delete cascade,
    account_id varchar(36) not null,
    channel_id varchar(36) not null,
    primary key (client_key_id, account_id, channel_id),
    foreign key (account_id, channel_id) references oauth_account_channels(account_id, channel_id) on delete cascade
);
create index idx_client_key_denied_oauth_accounts_target
    on client_key_denied_oauth_accounts(channel_id, account_id);
