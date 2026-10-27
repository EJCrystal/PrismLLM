alter table model_prices
    add column current_version integer not null default 1,
    add column effective_from bigint not null default 0;

alter table model_default_prices
    add column current_version integer not null default 1,
    add column effective_from bigint not null default 0;

create table model_price_versions (
    id varchar(36) primary key,
    channel_id varchar(36) not null references channels(id) on delete cascade,
    upstream_model varchar(200) not null,
    version integer not null,
    currency varchar(8) not null,
    input_price_per_million numeric(18, 8) not null,
    output_price_per_million numeric(18, 8) not null,
    cached_input_price_per_million numeric(18, 8),
    cached_input_write_price_per_million numeric(18, 8),
    pricing_mode varchar(20) not null default 'token',
    per_request_price numeric(18, 8),
    price_tiers_json text not null default '[]',
    effective_from bigint not null,
    status varchar(20) not null default 'active',
    created_at bigint not null,
    unique (channel_id, upstream_model, version)
);

create index idx_model_price_versions_lookup
    on model_price_versions (channel_id, upstream_model, effective_from desc);

create table model_default_price_versions (
    id varchar(36) primary key,
    model_id varchar(200) not null,
    version integer not null,
    currency varchar(8) not null,
    input_price_per_million numeric(18, 8) not null,
    output_price_per_million numeric(18, 8) not null,
    cached_input_price_per_million numeric(18, 8),
    cached_input_write_price_per_million numeric(18, 8),
    pricing_mode varchar(20) not null default 'token',
    per_request_price numeric(18, 8),
    price_tiers_json text not null default '[]',
    effective_from bigint not null,
    status varchar(20) not null default 'active',
    created_at bigint not null,
    unique (model_id, version)
);

create index idx_model_default_price_versions_lookup
    on model_default_price_versions (model_id, effective_from desc);

alter table request_logs
    add column price_version integer,
    add column price_version_id varchar(36);

-- 将现有渠道单价数据回填为第 1 版
insert into model_price_versions (
    id, channel_id, upstream_model, version, currency,
    input_price_per_million, output_price_per_million,
    cached_input_price_per_million, cached_input_write_price_per_million,
    pricing_mode, per_request_price, price_tiers_json,
    effective_from, status, created_at
)
select
    id, channel_id, upstream_model, 1, currency,
    input_price_per_million, output_price_per_million,
    cached_input_price_per_million, cached_input_write_price_per_million,
    pricing_mode, per_request_price, price_tiers_json,
    created_at, 'active', created_at
from model_prices;

update model_prices set effective_from = created_at;

-- 将现有模型默认价数据回填为第 1 版
insert into model_default_price_versions (
    id, model_id, version, currency,
    input_price_per_million, output_price_per_million,
    cached_input_price_per_million, cached_input_write_price_per_million,
    pricing_mode, per_request_price, price_tiers_json,
    effective_from, status, created_at
)
select
    id, model_id, 1, currency,
    input_price_per_million, output_price_per_million,
    cached_input_price_per_million, cached_input_write_price_per_million,
    pricing_mode, per_request_price, price_tiers_json,
    created_at, 'active', created_at
from model_default_prices;

update model_default_prices set effective_from = created_at;
