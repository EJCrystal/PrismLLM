-- 订阅账号只服务官方端点：端点由代码常量固定，系统按 provider 置备一条隐藏渠道。
-- 账号不再归属用户自建渠道，一个账号只关联它的 provider 渠道。
alter table channels add column kind varchar(16) not null default 'custom';
alter table channels add column provider varchar(32);

alter table channels add constraint ck_channels_kind check (kind in ('custom', 'provider'));
alter table channels add constraint ck_channels_provider
    check ((kind = 'provider' and provider is not null)
        or (kind = 'custom' and provider is null));

create unique index uk_channels_provider on channels (provider) where kind = 'provider';

-- 迁移前一个账号可能挂在多个渠道上；先收敛成一行，置备器随后把它改指 provider 渠道。
delete from oauth_account_channels
where ctid not in (
    select max(ctid) from oauth_account_channels group by account_id
);

create unique index uk_oauth_account_channels_single on oauth_account_channels (account_id);
