alter table model_prices
    add column cached_input_write_price_per_million numeric(18,8),
    add column pricing_mode varchar(20) not null default 'token',
    add column per_request_price numeric(18,8),
    add column price_tiers_json text not null default '[]';

alter table model_default_prices
    add column cached_input_write_price_per_million numeric(18,8),
    add column pricing_mode varchar(20) not null default 'token',
    add column per_request_price numeric(18,8),
    add column price_tiers_json text not null default '[]';

alter table request_logs
    add column cached_write_tokens bigint,
    add column cached_input_write_price_per_million numeric(18,8),
    add column pricing_mode varchar(20) not null default 'token',
    add column per_request_price numeric(18,8),
    add column price_tiers_json text not null default '[]';
