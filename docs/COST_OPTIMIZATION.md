# Cloud Capacity Planning & Cost Optimization Guide

This guide details infrastructure right-sizing, Spot/Preemptible instance utilization, and cloud cost optimization strategies for OptiQueue.

---

## 1. Spot Instance Architecture for Worker Nodes

OptiQueue's decoupled scheduler and worker architecture is resilient to abrupt node preemption:
- **Scheduler Nodes**: Run on On-Demand instances (to maintain consistent leadership).
- **Worker Pools**: Scale dynamically on AWS EC2 Spot / GCP Spot VMs (typically 60-80% cheaper).
- **Preemption Safety**: If a Spot node receives a 2-minute termination notice, `SIGTERM` triggers cooperative drain. Any unfinished tasks are automatically reclaimed by `FailoverDetector` within 30 seconds.

---

## 2. PostgreSQL & Redis Sizing Estimates

| Metric | Low Volume (< 100 req/s) | Medium Volume (1k req/s) | High Volume (5k+ req/s) |
|---|---|---|---|
| **PostgreSQL** | `db.t4g.medium` (2 vCPU, 4GB) | `db.m6g.xlarge` (4 vCPU, 16GB) | `db.r6g.2xlarge` (8 vCPU, 64GB) |
| **Redis** | `cache.t4g.micro` (0.5GB) | `cache.m6g.large` (6.3GB) | `cache.r6g.large` (13GB) |
| **Worker Replicas** | 2 | 6 | 15 - 25 |
