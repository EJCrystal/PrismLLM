-- 计费币种统一 USD（issue #222）：价格配置、价格快照与费用账本都不接受其他币种。
-- 历史数据兜底（CodeRabbit review）：旧版校验允许 CNY/EUR 等币种，若其他部署环境
-- 存量数据里有非 USD 记录，CHECK 约束会直接让迁移失败。非 USD 的历史费用无法可靠
-- 换算，直接标 USD 会造成错误计费——这里把这类记录的费用字段置 NULL（视为未计费），
-- 保留日志行本身。生产库已实证全部为 USD/NULL，以下 UPDATE 是对其他环境的防御。
update request_logs
set cost_amount = null,
    cost_currency = null
where cost_currency is not null
  and cost_currency <> 'USD';

update model_prices
set currency = 'USD'
where currency <> 'USD';

update model_default_prices
set currency = 'USD'
where currency <> 'USD';

alter table model_prices
    add constraint ck_model_prices_currency_usd check (currency = 'USD');
alter table model_default_prices
    add constraint ck_model_default_prices_currency_usd check (currency = 'USD');
alter table request_logs
    add constraint ck_request_logs_cost_currency_usd check (cost_currency is null or cost_currency = 'USD');
