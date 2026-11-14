# Monitoring and alerting

`prometheus.yml` scrapes the gateway's `/metrics` endpoint and probes `/health/ready` through Blackbox Exporter. Mount it as `/etc/prometheus/prometheus.yml` and mount `alerts.yml` as `/etc/prometheus/alerts.yml`.

`monitoring/docker-compose.yml` provides both collectors. Start the gateway first, then run:

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

The example joins the gateway compose network `prism-llm_default`; adjust the external network name if your Compose project uses a different name. Prometheus is available at `http://localhost:9090` and Blackbox at `http://localhost:9115`.

Unknown model IDs rejected during routing are recorded under the fixed `alias="unresolved"` label, preventing request-controlled label-cardinality growth.

The included rules cover:

- scrape target down and readiness probe failure;
- request error rate above 5% for 10 minutes;
- complete-request p95 latency above 10 seconds for 10 minutes;
- sustained quota rejections;
- management audit persistence failures;
- pricing/quota settlement failures and lost request logs;
- per-channel upstream attempt failure rate above 20% for 10 minutes;
- candidates entering cooldown (per failure reason, fail-over with fail-open fallback).

The thresholds are starting points for an internal gateway. Tune them against normal traffic, and route Alertmanager notifications to the owning team.

## Metrics

- `gateway_requests_total{outcome,status,alias,channel}` counts completed requests. Streaming failures carry `outcome="error"` even when HTTP headers were already committed as 200.
- `gateway_request_duration_seconds` measures total request latency.
- `gateway_quota_rejections_total{reason}` counts `429` responses produced by daily token/cost, per-minute request, or key/team concurrency limits.
- `gateway_admin_audit_write_failures_total` counts management audit rows that could not be persisted.
- `gateway_request_settlement_failures_total{stage}` counts pricing, quota settlement or request-finalization failures. A completed response is not replayed; a quota write failure may leave the daily ledger incomplete and requires investigation.
- `gateway_request_log_write_failures_total` counts request logs that failed to persist; the asynchronous writer continues with the next record without retrying.
- `gateway_request_log_dropped_total` counts records rejected by the bounded queue, including submissions after it closes. Log-based usage reports are best-effort views, not a guaranteed financial ledger.
- `gateway_upstream_attempts_total{channel,outcome}` tracks each channel attempt, including retries.
- `gateway_upstream_errors_total{channel,code}` remains available for detailed upstream error classification.
- `gateway_upstream_retries_total{from_channel,to_channel,reason}` counts retry and failover transitions.
- `gateway_upstream_cooldown_skips_total{channel}` counts candidates skipped because they are cooling down.
- `gateway_upstream_cooldown_started_total{channel,reason}` counts cooldown starts; `reason` is `transient`, `rate_limited`, `auth_failure`, or `ttft_slow`.
- `gateway_database_ready` is updated when `/health/ready` is checked.

## Trace correlation

The data plane accepts a W3C `traceparent` header. It preserves the trace ID, creates a gateway span ID, forwards `traceparent` upstream, and returns both `traceparent` and `X-Trace-Id`. The trace ID is stored with request metadata and can be searched or copied from the request-log details view.

Validate rule syntax before deployment:

```bash
docker run --rm -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  --entrypoint /bin/promtool prom/prometheus:v3.7.2 \
  check rules /etc/prometheus/alerts.yml
```
