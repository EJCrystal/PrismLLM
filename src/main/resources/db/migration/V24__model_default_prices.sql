-- 归一化历史渠道单价中的上游模型名，保留最近更新的一条，避免 trim 后重复。
delete from channel_models c
using channel_models d
where c.channel_id = d.channel_id
  and btrim(c.upstream_model) = btrim(d.upstream_model)
  and (
      c.created_at < d.created_at
      or (c.created_at = d.created_at and c.upstream_model > d.upstream_model)
  );

update channel_models
set upstream_model = btrim(upstream_model);

update alias_candidates
set upstream_model = btrim(upstream_model);

delete from model_prices p
using model_prices q
where p.channel_id = q.channel_id
  and btrim(p.upstream_model) = btrim(q.upstream_model)
  and (
      p.updated_at < q.updated_at
      or (p.updated_at = q.updated_at and p.id < q.id)
  );

update model_prices
set upstream_model = btrim(upstream_model);

create table model_default_prices (
    id varchar(36) primary key,
    model_id varchar(200) not null unique,
    currency varchar(8) not null,
    input_price_per_million decimal(18, 8) not null,
    output_price_per_million decimal(18, 8) not null,
    cached_input_price_per_million decimal(18, 8),
    created_at bigint not null,
    updated_at bigint not null
);
