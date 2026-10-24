alter table request_logs
    add column upstream_attempts integer;

alter table request_logs
    add constraint request_logs_upstream_attempts_non_negative
        check (upstream_attempts is null or upstream_attempts >= 0);
