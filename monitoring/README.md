# Monitoring and alerting

`prometheus.yml` scrapes the gateway's `/metrics` endpoint and probes `/health/ready` through Blackbox Exporter. Mount it as `/etc/prometheus/prometheus.yml` and mount `alerts.yml` as `/etc/prometheus/alerts.yml`.

The included rules cover:

- scrape target down and readiness probe failure;
- request error rate above 5% for 10 minutes;
- complete-request p95 latency above 10 seconds for 10 minutes;
- sustained quota rejections;
- per-channel upstream attempt failure rate above 20% for 10 minutes.

The thresholds are starting points for an internal gateway. Tune them against normal traffic, and route Alertmanager notifications to the owning team.

## Metrics

- `gateway_requests_total{outcome,status,alias,channel}` counts completed requests. Streaming failures carry `outcome="error"` even when HTTP headers were already committed as 200.
- `gateway_request_duration_seconds` measures total request latency.
- `gateway_quota_rejections_total{reason}` counts `429` responses produced by token, cost, or concurrency limits.
- `gateway_upstream_attempts_total{channel,outcome}` tracks each channel attempt, including retries.
- `gateway_upstream_errors_total{channel,code}` remains available for detailed upstream error classification.
- `gateway_database_ready` is updated when `/health/ready` is checked.

## Trace correlation

The data plane accepts a W3C `traceparent` header. It preserves the trace ID, creates a gateway span ID, forwards `traceparent` upstream, and returns both `traceparent` and `X-Trace-Id`. The trace ID is stored with request metadata and can be searched or copied from the request-log details view.

Validate rule syntax before deployment:

```bash
docker run --rm -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  --entrypoint /bin/promtool prom/prometheus:v3.7.2 \
  check rules /etc/prometheus/alerts.yml
```
