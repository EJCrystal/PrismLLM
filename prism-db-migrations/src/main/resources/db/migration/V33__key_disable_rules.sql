-- 规则列表与按规则分桶的失败计数。
-- rules 为空表示使用代码内置的默认规则；计数为空表示尚未累计。
alter table key_auto_disable_settings add column rules text;

alter table channel_key_health add column rule_counters text;
