alter table request_logs
    add column if not exists stream boolean;
