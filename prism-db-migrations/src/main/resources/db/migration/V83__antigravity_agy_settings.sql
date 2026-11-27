create table antigravity_agy_settings (
    id integer primary key,
    enabled boolean not null default false,
    proxy text not null,
    updated_at bigint not null,
    constraint antigravity_agy_settings_singleton check (id = 1)
);
