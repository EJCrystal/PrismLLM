alter table client_keys
    add column requests_per_minute integer;

alter table client_keys
    add constraint client_keys_requests_per_minute_non_negative
        check (requests_per_minute is null or requests_per_minute >= 0);

create table team_limits (
    team varchar(100) primary key,
    requests_per_minute integer,
    max_concurrent_requests integer,
    created_at bigint not null,
    updated_at bigint not null,
    constraint team_limits_requests_per_minute_non_negative
        check (requests_per_minute is null or requests_per_minute >= 0),
    constraint team_limits_max_concurrent_requests_positive
        check (max_concurrent_requests is null or max_concurrent_requests > 0),
    constraint team_limits_has_a_limit
        check (requests_per_minute is not null or max_concurrent_requests is not null)
);
