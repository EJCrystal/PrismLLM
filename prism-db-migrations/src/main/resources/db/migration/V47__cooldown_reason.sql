-- 冷却触发原因，用于管理面展示与重启恢复。
alter table cooldown_state add column reason varchar(40);
