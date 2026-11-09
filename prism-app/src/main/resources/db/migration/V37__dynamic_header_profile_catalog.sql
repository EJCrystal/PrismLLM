create table upstream_header_catalog_state (
    id integer primary key,
    checked_at bigint not null,
    success_at bigint,
    last_error text,
    constraint upstream_header_catalog_state_singleton check (id = 1)
);

create table upstream_header_profile_versions (
    profile_id varchar(40) not null,
    version varchar(100) not null,
    name varchar(100) not null,
    headers text not null,
    source varchar(500) not null,
    fetched_at bigint not null,
    primary key (profile_id, version)
);

create index upstream_header_profile_versions_fetched_idx
    on upstream_header_profile_versions(profile_id, fetched_at desc);

create table upstream_header_profile_pins (
    profile_id varchar(40) primary key,
    version varchar(100) not null,
    pinned_at bigint not null,
    constraint upstream_header_profile_pins_version_fk
        foreign key (profile_id, version)
        references upstream_header_profile_versions(profile_id, version)
        on delete restrict
);
