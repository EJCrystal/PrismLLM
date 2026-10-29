alter table channels add column request_overrides text not null default '{}';

create table upstream_request_settings (
    id integer primary key,
    overrides text not null,
    updated_at bigint not null,
    constraint upstream_request_settings_singleton check (id = 1)
);
