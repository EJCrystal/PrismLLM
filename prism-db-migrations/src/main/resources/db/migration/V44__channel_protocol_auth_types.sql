alter table channels
    add column protocol_auth_types text not null default '{}';
