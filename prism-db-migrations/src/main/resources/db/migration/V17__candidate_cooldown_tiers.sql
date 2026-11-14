-- 候选档位：position 从严格全序升级为优先级档位，同档候选分摊流量。
alter table alias_candidates
    drop constraint alias_candidates_alias_id_position_key;

-- 候选冷却状态快照。内存为准，这里只用于重启恢复。
create table cooldown_state (
    state_key varchar(300) primary key,
    consecutive_failures integer not null,
    cooldown_until bigint not null,
    updated_at bigint not null
);
