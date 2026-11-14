create table model_prices (
    id varchar(36) primary key,
    channel_id varchar(36) not null references channels(id) on delete cascade,
    upstream_model varchar(200) not null,
    currency varchar(8) not null,
    input_price_per_million numeric(18,8) not null,
    output_price_per_million numeric(18,8) not null,
    cached_input_price_per_million numeric(18,8),
    created_at bigint not null,
    updated_at bigint not null,
    constraint uq_model_prices_channel_model unique (channel_id, upstream_model)
);

create index idx_model_prices_channel on model_prices(channel_id);

alter table request_logs
    add column cost_amount numeric(18,8),
    add column cost_currency varchar(8),
    add column input_price_per_million numeric(18,8),
    add column output_price_per_million numeric(18,8),
    add column cached_input_price_per_million numeric(18,8);
