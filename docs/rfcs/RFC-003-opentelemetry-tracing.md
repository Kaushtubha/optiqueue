# RFC-003: Distributed Tracing with OpenTelemetry

- **Status**: Proposed
- **Author**: OptiQueue Core Team
- **Date**: 2026-11-02

---

## 1. Motivation
When jobs fail or experience latency spikes across distributed workers, operators need end-to-end trace correlation linking the HTTP submission, scheduling loop, and worker thread execution.

---

## 2. Specification
- Propagate W3C `traceparent` headers through job payload metadata.
- OpenTelemetry spans for:
  - `optiqueue.job.submit`
  - `optiqueue.scheduler.poll`
  - `optiqueue.worker.execute`
