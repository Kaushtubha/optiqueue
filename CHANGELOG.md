# Changelog

All notable changes to the OptiQueue project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.2.0] - 2026-11-02

### Added
- Complete production Kubernetes deployment manifests (`k8s/`) including KEDA autoscaling.
- Disaster recovery, WAL backup procedures, and multi-region resilience runbooks.
- RFC architectural proposals for virtual queue partitioning and webhook event streams.
- Operational developer CLI tools: `scripts/dlq_manager.py`, `scripts/simulate_load.py`, `scripts/redis_ping_benchmark.py`.
- Comprehensive JavaDocs across core configuration, DTO, exception handling, and scheduler beans.

---

## [1.1.0] - 2026-10-31

### Added
- Standardized GitHub issue templates and Pull Request review templates.
- Relational PostgreSQL database schema specifications with B-tree composite index designs.
- Distributed token bucket rate limiting architecture documentation.
- Custom bounded `JobBlockingQueue` documentation with Goetz thread-pool mathematical formulas.
- Prometheus telemetry metrics catalog and Grafana alerting rule thresholds.
- Chaos engineering test simulation guides.

---

## [1.0.0] - 2026-10-25

### Added
- Initial production release of OptiQueue: Distributed High-Throughput Job Execution Engine.
- Earliest Deadline First (EDF) priority scheduling with min-heap queue.
- Distributed failover detector with Redis worker heartbeats and leases.
- Exponential backoff retry engine with jitter calculation.
- REST API endpoints for job submission, cancellation, status, and telemetry.
- Pluggable handler registry for `EMAIL_SEND`, `WEBHOOK_DISPATCH`, and `REPORT_GEN`.
