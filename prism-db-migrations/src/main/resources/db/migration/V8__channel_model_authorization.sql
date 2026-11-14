create table channel_models (
    channel_id varchar(36) not null references channels (id) on delete cascade,
    upstream_model varchar(200) not null,
    created_at bigint not null,
    primary key (channel_id, upstream_model)
);

create index idx_channel_models_model on channel_models (upstream_model);

insert into channel_models (channel_id, upstream_model, created_at)
select channel_id, upstream_model, extract(epoch from now())::bigint * 1000
from (
    select channel_id, upstream_model from alias_candidates
    union
    select channel_id, upstream_model from model_prices
) existing_models
on conflict (channel_id, upstream_model) do nothing;
