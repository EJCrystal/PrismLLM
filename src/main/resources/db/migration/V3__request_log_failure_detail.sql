alter table request_logs
    add column failure_stage varchar(20);

alter table request_logs
    add column error_message text;

alter table request_logs
    add column upstream_error_code varchar(200);
