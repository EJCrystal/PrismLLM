alter table request_logs
    add column if not exists attempts_json text not null default '[]';

update request_logs
set attempts_json = '[]'
where attempts_json is null;
