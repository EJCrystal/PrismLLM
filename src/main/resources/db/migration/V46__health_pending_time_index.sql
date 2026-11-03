-- Keep V45 checksums stable; this small partial index is created during startup migration.
create index if not exists idx_request_logs_health_pending_created
    on request_logs (created_at, id) where not health_indexed;
drop index if exists idx_request_logs_health_pending;
