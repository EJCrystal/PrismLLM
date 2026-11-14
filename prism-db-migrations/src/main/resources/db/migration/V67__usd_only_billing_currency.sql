-- 计费币种统一 USD（issue #222）：价格配置、价格快照与费用账本都不接受其他币种。
-- 历史数据兜底：issue 验证过现有部署全部为 USD/NULL；但其他部署若因旧版校验
-- 留下非 USD 记录，这里不做任何静默改写——把 CNY 金额改标 USD 等于把未换算的
-- 原币金额混进 USD 账本（正是本任务要阻止的错误扣费）。守卫直接中止迁移，
-- 管理员自行决定换算或删除后再重跑。
do $$
declare
    non_usd_logs bigint;
    non_usd_prices bigint;
    non_usd_default_prices bigint;
begin
    select count(*) into non_usd_logs
    from request_logs
    where cost_currency is not null and cost_currency <> 'USD';

    select count(*) into non_usd_prices
    from model_prices
    where currency <> 'USD';

    select count(*) into non_usd_default_prices
    from model_default_prices
    where currency <> 'USD';

    if non_usd_logs > 0 or non_usd_prices > 0 or non_usd_default_prices > 0 then
        raise exception using
            message = format(
                'V67 需要人工处理存量非 USD 数据后再升级：request_logs %s 条、model_prices %s 条、model_default_prices %s 条。请先换算为 USD 或删除无效配置（request_logs 非法费用可置 NULL 视为未计费），再重跑迁移。',
                non_usd_logs, non_usd_prices, non_usd_default_prices
            ),
            hint = '不要直接把原币金额改标 USD：未换算金额会混入 USD 账本与额度扣减（issue #222）';
    end if;
end
$$;

alter table model_prices
    add constraint ck_model_prices_currency_usd check (currency = 'USD');
alter table model_default_prices
    add constraint ck_model_default_prices_currency_usd check (currency = 'USD');
alter table request_logs
    add constraint ck_request_logs_cost_currency_usd check (cost_currency is null or cost_currency = 'USD');
