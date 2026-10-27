drop table model_price_versions;
drop table model_default_price_versions;

-- 仅配置了未来生效时间、尚未真正生效的价格不再保留，避免移除定时功能后提前生效。
delete from model_prices where effective_from > (extract(epoch from now()) * 1000)::bigint;
delete from model_default_prices where effective_from > (extract(epoch from now()) * 1000)::bigint;

alter table model_prices
    drop column current_version,
    drop column effective_from;

alter table model_default_prices
    drop column current_version,
    drop column effective_from;

alter table request_logs
    drop column price_version,
    drop column price_version_id;
