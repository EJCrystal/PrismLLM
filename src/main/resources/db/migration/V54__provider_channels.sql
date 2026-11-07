-- 订阅账号只服务官方端点：端点由代码常量固定，系统按 provider 置备一条隐藏渠道。
-- 账号与渠道的关联仍是多对多，只有系统托管的账号才收敛到唯一一条 provider 渠道。
alter table channels add column kind varchar(16) not null default 'custom';
alter table channels add column provider varchar(32);

alter table channels add constraint ck_channels_kind check (kind in ('custom', 'provider'));
alter table channels add constraint ck_channels_provider
    check ((kind = 'provider' and provider is not null)
        or (kind = 'custom' and provider is null));

create unique index uk_channels_provider on channels (provider) where kind = 'provider';

-- 历史账号可能手工挂了多个渠道；这里不删除它们，只标记出挂在系统渠道上的托管关联。
-- 置备器随后把可服务的账号改指 provider 渠道，未实现托管协议的 provider 仍保留原有渠道。
alter table oauth_account_channels add column managed boolean not null default false;

update oauth_account_channels c
set managed = true
from channels ch
where ch.id = c.channel_id
  and ch.kind = 'provider';

create unique index uk_oauth_account_channels_managed
    on oauth_account_channels (account_id)
    where managed;

-- 迁移前就存在的账号需要改挂到系统渠道；逐账号搬迁，成功一个清一个标记，
-- 中途失败不必重放迁移，下次启动继续。协议尚未实现的 provider 保持待搬迁。
alter table oauth_accounts add column provider_channel_pending boolean not null default false;
update oauth_accounts set provider_channel_pending = true;
