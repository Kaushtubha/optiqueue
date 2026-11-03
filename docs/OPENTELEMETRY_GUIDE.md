# OpenTelemetry Distributed Tracing & Sampling Guide

This document describes how to configure OpenTelemetry distributed tracing across OptiQueue components.

---

## 1. Tracing Pipeline Architecture

```
[ OptiQueue App ] --(OTLP gRPC :4317)--> [ OpenTelemetry Collector ]
                                                   |
                        +--------------------------+--------------------------+
                        |                                                     |
                        v                                                     v
               [ Jaeger / Tempo ]                                   [ AWS X-Ray / GCP Trace ]
```

---

## 2. Span Hierarchy for Background Jobs

1. `optiqueue.api.submit`: Spans the incoming HTTP request, validation, and database insert.
2. `optiqueue.scheduler.poll`: Spans the priority queue fetch and Redis lock lease.
3. `optiqueue.worker.execute`: Child span linked across thread boundaries via `Job.id` / traceparent metadata.
4. `optiqueue.handler.<type>`: Leaf span covering third-party API / email / webhook execution.

---

## 3. Sampling Configuration

To balance observability depth against storage costs:
- **Default Workloads**: 5% probabilistic trace sampling (`OTEL_TRACES_SAMPLER=parentbased_traceidratio`, `OTEL_TRACES_SAMPLER_ARG=0.05`).
- **Failures & DLQ**: 100% tail-based sampling on error spans (`status_code == ERROR`).
