# Grafana Dashboard Provisioning Specification

This document provides recommended dashboard layouts and JSON panel definitions for OptiQueue monitoring in Grafana.

---

## 1. Dashboard Variables

- `$environment`: Filter by runtime environment (`prod`, `staging`, `dev`)
- `$instance`: Filter by specific application pod / hostname
- `$job_type`: Filter by registered job handler types (`EMAIL_SEND`, `WEBHOOK_DISPATCH`, `REPORT_GEN`)

---

## 2. Key Visualizations

1. **Queue Throughput (Graph Panel)**:
   - Query: `sum(rate(optiqueue_jobs_total[1m])) by (status)`
2. **Execution Latency Percentiles (Graph Panel)**:
   - Query: `histogram_quantile(0.99, sum(rate(optiqueue_job_duration_seconds_bucket[5m])) by (le))`
3. **Dead-Letter Accumulation Rate (Stat Panel)**:
   - Query: `sum(increase(optiqueue_jobs_total{status="DEAD_LETTER"}[1h]))`
4. **Worker Thread Saturation (Gauge Panel)**:
   - Query: `sum(optiqueue_active_workers) / sum(optiqueue_worker_pool_capacity)`
