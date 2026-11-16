-- Persist an explicit ordered credential list for each public model. Empty lists are configured, too.
create table model_credential_routes (
    model_id varchar(200) primary key,
    revision bigint not null default 0 check (revision >= 0),
    updated_at bigint not null
);

-- Keep unavailable/deleted identities for administration; only the parent route has a foreign key.
create table model_credential_route_entries (
    model_id varchar(200) not null references model_credential_routes (model_id) on delete cascade,
    position integer not null check (position >= 0),
    channel_id varchar(36) not null,
    credential_type varchar(16) not null check (credential_type in ('static', 'oauth')),
    credential_id varchar(36) not null,
    primary key (model_id, position),
    unique (model_id, channel_id, credential_type, credential_id)
);

-- This is the only bootstrap: future models/credentials require an explicit administrator save.
-- Include public models without a currently usable credential so an empty list never invokes a fallback.
insert into model_credential_routes (model_id, revision, updated_at)
select distinct coalesce(m.canonical_model_id, m.upstream_model), 0,
       (extract(epoch from transaction_timestamp()) * 1000)::bigint
from channel_models m
join channels c on c.id = m.channel_id
where c.enabled and m.catalog_active;

-- Preserve the previous priority comparator only for this migration. Static credentials must be ACTIVE;
-- OAuth accounts must be enabled, authorized, catalog-compatible and serve the channel's native protocol.
with candidates as (
    select coalesce(m.canonical_model_id, m.upstream_model) as model_id,
           c.id as channel_id, 'static' as credential_type, k.id as credential_id,
           k.priority as credential_priority, c.priority as channel_priority,
           c.name as channel_name, k.name as credential_name, m.upstream_model
    from channels c
    join channel_models m on m.channel_id = c.id and m.catalog_active
    join channel_keys k on k.channel_id = c.id and k.state = 'ACTIVE'
    join channel_key_models km on km.key_id = k.id and km.upstream_model = m.upstream_model
    where c.enabled
      and exists (select 1 from unnest(string_to_array(c.protocols, ',')) protocol
                  where btrim(protocol) in ('chat_completions', 'responses', 'messages', 'gemini'))
    union all
    select coalesce(m.canonical_model_id, m.upstream_model),
           c.id, 'oauth', a.id, a.priority, c.priority, c.name, a.name, m.upstream_model
    from channels c
    join channel_models m on m.channel_id = c.id and m.catalog_active
    join oauth_account_channels link on link.channel_id = c.id
    join oauth_accounts a on a.id = link.account_id and a.enabled and a.auth_state = 'ACTIVE'
    where c.enabled
      and ((a.provider = 'chatgpt' and 'responses' = any(string_to_array(c.protocols, ',')))
        or (a.provider = 'antigravity' and 'gemini' = any(string_to_array(c.protocols, ','))))
      and (not exists (select 1 from oauth_account_models am where am.account_id = a.id)
        or exists (select 1 from oauth_account_models am
                   where am.account_id = a.id and am.upstream_model = m.upstream_model))
      and (a.model_catalog is null or exists (
          select 1 from jsonb_array_elements(a.model_catalog::jsonb) entry
          where entry ->> 'upstreamModel' = m.upstream_model
      ))
), first_variant as (
    select *, row_number() over (
        partition by model_id, channel_id, credential_type, credential_id
        order by upstream_model collate "C"
    ) as variant_rank
    from candidates
), ordered as (
    select *, row_number() over (
        partition by model_id
        order by credential_priority, channel_priority, channel_name collate "C",
                 credential_name collate "C", credential_id collate "C", upstream_model collate "C",
                 credential_type collate "C", channel_id collate "C"
    ) - 1 as position
    from first_variant where variant_rank = 1
)
insert into model_credential_route_entries (model_id, position, channel_id, credential_type, credential_id)
select model_id, position::integer, channel_id, credential_type, credential_id
from ordered;
