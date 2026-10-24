alter table client_keys
    add column team varchar(100);

alter table request_logs
    add column team varchar(100);

create index idx_request_logs_team_created_at on request_logs (team, created_at desc);
create index idx_request_logs_client_key_created_at on request_logs (client_key_id, created_at desc);
