# RFC-001: Dynamic Queue Partitioning

- **Status**: Proposed
- **Author**: OptiQueue Core Team
- **Date**: 2026-11-02

---

## 1. Context & Motivation
Currently, all jobs are stored in a single `jobs` PostgreSQL table and polled into an in-memory priority queue. At scale (>10,000 jobs/sec), row-level locks on the single table become a contention bottleneck.

---

## 2. Proposed Architecture
1. Introduce **Virtual Partitions** (`partition_id = hash(job_type) % N`).
2. Dedicated worker threads poll specific partition subsets, eliminating database lock contention.
3. Redis key partitioning: `optiqueue:queue:{partition_id}`.
