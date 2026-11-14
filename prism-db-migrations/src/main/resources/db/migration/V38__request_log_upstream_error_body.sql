alter table request_logs
    add column if not exists upstream_error_body text;
