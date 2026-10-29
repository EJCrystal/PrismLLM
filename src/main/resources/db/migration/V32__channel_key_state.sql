-- 密钥状态从单一布尔开关拆成四态，区分人工停用与自动停用。
-- 回填依据 auto_disabled_at：有停用时间的是自动停用，否则是人工停用。
alter table channel_keys add column state varchar(20);

alter table channel_keys add column auto_disable_until bigint;

update channel_keys
set state = case
    when enabled then 'ACTIVE'
    when auto_disabled_at is not null then 'AUTO_PERMANENT'
    else 'MANUAL_DISABLED'
end;

alter table channel_keys alter column state set not null;

alter table channel_keys
    add constraint ck_channel_keys_state
        check (state in ('ACTIVE', 'MANUAL_DISABLED', 'AUTO_TEMPORARY', 'AUTO_PERMANENT'));

-- 只有临时停用可以带到期时间；永久停用与人工停用必须为空。
alter table channel_keys
    add constraint ck_channel_keys_auto_disable_until
        check (state = 'AUTO_TEMPORARY' or auto_disable_until is null);

create index idx_channel_keys_state_until on channel_keys (state, auto_disable_until)
    where state = 'AUTO_TEMPORARY';

alter table channel_keys drop column enabled;
