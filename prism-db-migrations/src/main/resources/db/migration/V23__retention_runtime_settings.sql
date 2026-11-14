create table retention_settings (
    id integer primary key,
    cleanup_enabled boolean not null,
    updated_at bigint not null,
    constraint retention_settings_singleton check (id = 1)
);
