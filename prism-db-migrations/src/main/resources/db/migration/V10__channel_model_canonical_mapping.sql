alter table channel_models
    add column canonical_model_id varchar(200),
    add column mapping_source varchar(32);

create index idx_channel_models_canonical on channel_models (canonical_model_id);
