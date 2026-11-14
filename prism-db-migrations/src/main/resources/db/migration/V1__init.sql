create table channels (
    id varchar(36) primary key,
    name varchar(100) not null unique,
    base_url varchar(500) not null,
    credential_ciphertext text not null,
    credential_nonce text not null,
    credential_key_version integer not null,
    auth_type varchar(20) not null default 'bearer',
    enabled boolean not null default true,
    created_at bigint not null,
    updated_at bigint not null
);

create table model_aliases (
    id varchar(36) primary key,
    alias varchar(100) not null unique,
    enabled boolean not null default true,
    created_at bigint not null,
    updated_at bigint not null
);

create table alias_candidates (
    id varchar(36) primary key,
    alias_id varchar(36) not null references model_aliases (id) on delete cascade,
    channel_id varchar(36) not null references channels (id) on delete restrict,
    upstream_model varchar(200) not null,
    position integer not null,
    unique (alias_id, position)
);

create index idx_alias_candidates_alias on alias_candidates (alias_id, position);
create index idx_alias_candidates_channel on alias_candidates (channel_id);

create table client_keys (
    id varchar(36) primary key,
    name varchar(100) not null,
    key_hash varchar(64) not null unique,
    key_prefix varchar(20) not null,
    enabled boolean not null default true,
    expires_at bigint,
    created_at bigint not null
);

create table admin_users (
    id varchar(36) primary key,
    username varchar(100) not null unique,
    password_hash text not null,
    created_at bigint not null
);

create table request_logs (
    id bigserial primary key,
    request_id varchar(64) not null,
    client_key_id varchar(36),
    alias varchar(100) not null,
    channel_id varchar(36),
    upstream_model varchar(200),
    status_code integer not null,
    first_token_ms bigint,
    total_ms bigint not null,
    input_tokens bigint,
    output_tokens bigint,
    cached_tokens bigint,
    total_tokens bigint,
    usage_known boolean not null default false,
    error_code varchar(100),
    created_at bigint not null
);

create index idx_request_logs_created_at on request_logs (created_at desc);
create index idx_request_logs_request_id on request_logs (request_id);
