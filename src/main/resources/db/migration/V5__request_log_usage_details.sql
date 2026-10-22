alter table request_logs
    add column reasoning_tokens bigint;

alter table request_logs
    add column client_key_name varchar(100);

alter table request_logs
    add column reasoning_effort varchar(40);
