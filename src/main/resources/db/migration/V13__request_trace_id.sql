alter table request_logs
    add column trace_id varchar(32);

create index idx_request_logs_trace_id on request_logs (trace_id);
