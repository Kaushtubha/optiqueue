# Metrics, Telemetry & Alerting Guide

OptiQueue exposes real-time operational telemetry through Spring Boot Actuator and Micrometer, formatted for direct ingestion by Prometheus and visualization in Grafana.

---

## 1. Metrics Catalog

| Metric Name | Type | Description | Labels / Tags |
|---|---|---|---|
| `optiqueue_jobs_total` | Counter | Cumulative count of jobs processed | `status`, `job_type` |
| `optiqueue_queue_depth` | Gauge | Current number of unassigned pending jobs | `priority_tier` |
| `optiqueue_job_duration_seconds` | Histogram | Latency distribution of job execution | `job_type`, `status` |
| `optiqueue_active_workers` | Gauge | Number of active worker execution threads | `worker_node_id` |
| `optiqueue_retries_total` | Counter | Count of retry attempts triggered | `job_type` |
| `optiqueue_dlq_total` | Counter | Total jobs routed to Dead-Letter Queue | `job_type`, `error_type` |
| `optiqueue_ratelimit_blocked` | Counter | Requests rejected due to quota exhaustion | `client_id` |

---

## 2. Recommended Prometheus Alert Rules

Configure the following alert rules in Prometheus to monitor cluster health:

### 2.1 Dead-Letter Queue Surge
Fires when excessive jobs exhaust retries within a 5-minute sliding window:
```yaml
- alert: OptiQueueDeadLetterSpike
  expr: increase(optiqueue_dlq_total[5m]) > 10
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Elevated dead-letter queue routing rate in OptiQueue"
    description: "More than 10 jobs routed to DLQ over the last 5 minutes. Check downstream API dependencies."
```

### 2.2 Elevated Execution Latency (P95 Spike)
Fires when the 95th percentile execution duration exceeds 2 seconds:
```yaml
- alert: OptiQueueHighExecutionLatency
  expr: histogram_quantile(0.95, sum(rate(optiqueue_job_duration_seconds_bucket[5m])) by (le)) > 2.0
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "P95 job execution latency exceeded 2.0s"
    description: "Worker threads may be experiencing I/O timeouts or CPU throttling."
```

### 2.3 Worker Pool Saturation
Fires when all worker threads are continually busy for more than 10 minutes:
```yaml
- alert: OptiQueueWorkerSaturation
  expr: optiqueue_active_workers / optiqueue_worker_pool_capacity >= 0.95
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Worker pool capacity approaching 100%"
    description: "Consider scaling out application instances or increasing max-pool-size."
```

---

## 3. Grafana Dashboard Panels

Recommended panels for the primary operational dashboard:
1. **Top Row (Executive KPIs)**: Total Throughput (jobs/sec), Active Workers, DLQ Count, Current Queue Depth.
2. **Middle Row (Latencies)**: P50, P90, P99 Job Duration Heatmap.
3. **Bottom Row (Infrastructure)**: PostgreSQL HikariCP Active Connections, Redis Cache Hit Rate, JVM Heap Usage.
