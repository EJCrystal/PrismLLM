-- 多币种移除：费用统一按 USD 计，删除 CNY/EUR 配额列与按币种分组的用量账本列。
alter table client_keys
    drop column if exists daily_cny_limit,
    drop column if exists daily_eur_limit;

alter table client_keys
    drop constraint if exists client_keys_daily_cny_limit_non_negative,
    drop constraint if exists client_keys_daily_eur_limit_non_negative;

alter table client_key_quota_usage
    drop column if exists cny_cost,
    drop column if exists eur_cost;
