-- 临时停用到期后只允许一个请求探测；probe_until 是探测租约，避免并发请求同时打上游。
alter table channel_keys add column probe_until bigint;

alter table channel_keys
    add constraint ck_channel_keys_probe_until
        check (probe_until is null or probe_until >= 0);

-- 只有临时停用可以持有探测租约；恢复、人工启停和永久停用时必须清空。
alter table channel_keys
    add constraint ck_channel_keys_probe_until_state
        check (probe_until is null or state = 'AUTO_TEMPORARY');
