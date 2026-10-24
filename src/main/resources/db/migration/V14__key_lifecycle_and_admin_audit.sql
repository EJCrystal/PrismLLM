alter table client_keys
    add column rotated_at bigint,
    add column revoked_at bigint;

alter table client_keys
    add constraint client_keys_revoked_must_be_disabled
        check (revoked_at is null or enabled = false);

create table admin_audit_logs (
    id bigserial primary key,
    actor varchar(100),
    action varchar(80) not null,
    method varchar(10) not null,
    path varchar(500) not null,
    status_code integer not null,
    result varchar(16) not null,
    request_id varchar(64) not null,
    source_ip varchar(100),
    error_code varchar(100),
    created_at bigint not null
);

create index idx_admin_audit_logs_created_at on admin_audit_logs (created_at desc);
create index idx_admin_audit_logs_actor_created_at on admin_audit_logs (actor, created_at desc);
