-- 计费币种统一 USD（issue #222）：价格配置、价格快照与费用账本都不接受其他币种。
-- 历史数据已核实全部为 USD 或 NULL；NULL 允许（未计费的请求没有币种）。
alter table model_prices
    add constraint ck_model_prices_currency_usd check (currency = 'USD');
alter table model_default_prices
    add constraint ck_model_default_prices_currency_usd check (currency = 'USD');
alter table request_logs
    add constraint ck_request_logs_cost_currency_usd check (cost_currency is null or cost_currency = 'USD');
