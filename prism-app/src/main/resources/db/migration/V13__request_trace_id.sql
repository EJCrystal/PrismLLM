alter table request_logs
    add column if not exists trace_id varchar(32);

create index if not exists idx_request_logs_trace_id on request_logs (trace_id);
