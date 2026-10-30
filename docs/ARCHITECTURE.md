# OptiQueue System Architecture

This document provides a comprehensive technical overview of the architecture, data flow, concurrency model, and distributed coordination mechanisms in **OptiQueue**.

---

## 1. High-Level Architecture

OptiQueue is a resilient, distributed job scheduling and queueing engine designed for high throughput, strict priority adherence, and fault-tolerant background task execution.

```
+-----------------------------------------------------------------------------------+
|                                 Client Applications                               |
+-----------------------------------------------------------------------------------+
                                         |
                                  HTTPS / REST API
                                         v
+-----------------------------------------------------------------------------------+
|                                API Ingestion Layer                                |
|  - ApiKeyAuthFilter (Authentication & Authorization)                              |
|  - RedisRateLimiter (Token Bucket Rate Limiting)                                  |
|  - JobController (REST Endpoint Handlers & Validation)                            |
+-----------------------------------------------------------------------------------+
                                         |
                 +-----------------------+-----------------------+
                 |                                               |
                 v                                               v
+-----------------------------------+           +-----------------------------------+
|         Primary Database          |           |       Distributed In-Memory       |
|           (PostgreSQL)            |           |              (Redis)              |
| - Persistent Job State Table      |           | - Sorted Sets (Priority Queues)   |
| - Audit & Dead-Letter Records     |           | - Distributed Locks (Redlock)     |
| - Idempotency Keys (Unique Idx)   |           | - Worker Heartbeats & Leases      |
+-----------------------------------+           +-----------------------------------+
                 ^                                               ^
                 |                                               |
+-----------------------------------------------------------------------------------+
|                              Scheduler & Dispatcher                               |
|  - SchedulerService (Polls pending jobs, checks deadlines)                        |
|  - JobPriorityQueue (In-memory bounded priority scheduling)                       |
|  - Dynamic Rate Regulation & Throttling                                           |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                Worker Execution Pool                              |
|  - WorkerPool (Configurable ThreadPoolExecutor)                                   |
|  - JobBlockingQueue (Fair, bounded dispatch)                                      |
|  - JobHandlerRegistry (Polymorphic task dispatch: Email, Webhook, Report, etc.)   |
+-----------------------------------------------------------------------------------+
                                         |
                 +-----------------------+-----------------------+
                 |                                               |
          [Job Succeeded]                                 [Job Failed]
                 |                                               |
                 v                                               v
        Mark COMPLETED in DB                          +----------------------+
                                                      |     RetryEngine      |
                                                      | - Exponential Backoff|
                                                      | - Jitter Calculation |
                                                      +----------------------+
                                                                 |
                                              +------------------+------------------+
                                              |                                     |
                                     (Attempts < Max)                      (Attempts >= Max)
                                              |                                     |
                                              v                                     v
                                   Requeue for Next Retry                 Route to Dead Letter Queue
```

---

## 2. Core Subsystems

### 2.1 API Ingestion Layer
- **`ApiKeyAuthFilter`**: Enforces secure access through API key verification headers on incoming HTTP requests.
- **`RedisRateLimiter`**: Employs a distributed token bucket algorithm implemented via Redis atomic commands, ensuring cluster-wide fairness and preventing resource exhaustion.
- **`JobController`**: Validates incoming job submissions, verifies payload schemas, and delegates job creation to the service layer.

### 2.2 Data Layer & Storage
- **PostgreSQL**: Serves as the authoritative persistent source of truth. Enforces ACID guarantees, idempotency constraints via unique indexes on `idempotency_key`, and historical execution logging.
- **Redis Cache & Coordination**:
  - **Priority Queues**: Stores ready-to-execute job identifiers in sorted sets (`ZSET`) keyed by calculated effective priority and deadline.
  - **Heartbeats**: Keeps ephemeral worker registrations with strict TTLs for liveness tracking.
  - **Distributed Locks**: Employs distributed lease locks to prevent race conditions during partition elections and failover recovery.

### 2.3 Scheduler & Dispatcher
- **Earliest Deadline First (EDF) with Priority Weighting**: Balances immediate deadline requirements against user-defined static job priorities (LOW, MEDIUM, HIGH, CRITICAL).
- **Batch Dispatch**: Periodically queries and dispatches jobs from persistent storage into active worker memory buffers, preventing database connection starvation.

### 2.4 Worker Execution Pool
- **`WorkerPool`**: Manages an elastic pool of dedicated worker threads with configurable core and maximum pool sizes.
- **`JobBlockingQueue`**: Bounded, thread-safe queue bridging the scheduler thread and execution threads.
- **`JobHandlerRegistry`**: Pluggable registry pattern mapping job types (`EMAIL`, `WEBHOOK`, `REPORT_GENERATION`) to specialized handlers.

### 2.5 Resilience & Failover
- **`FailoverDetector`**: Monitors worker heartbeats in Redis. If a worker ceases heartbeating beyond `failover-timeout-seconds`, its assigned in-flight jobs are recovered and returned to `PENDING` state.
- **`RetryEngine`**: Calculates backoff intervals using exponential formulas with truncated binary jitter to prevent thundering herds on upstream dependencies.
- **Dead-Letter Queue (DLQ)**: Terminal jobs exceeding retry thresholds are flagged as `DEAD_LETTER` with root-cause diagnostic metadata for administrative review.

---

## 3. Concurrency & Thread Safety

1. **Database-Level Idempotency**:
   Unique constraints on `idempotency_key` prevent duplicate job ingestion across concurrent API requests.
2. **Distributed Leases**:
   Failover detection and batch re-queue operations acquire distributed locks in Redis before modifying job state.
3. **Thread-Safe Data Structures**:
   Worker queues utilize `java.util.concurrent` primitives (`BlockingQueue`, `ReentrantLock`, `ConcurrentHashMap`) to guarantee race-free thread scheduling.

---

## 4. Scalability Model

- **Horizontal Scaling**: Multiple OptiQueue application instances can run concurrently behind a load balancer. Instances coordinate via Redis locks and PostgreSQL row-level locks (`SELECT FOR UPDATE SKIP LOCKED`).
- **Partitioning**: High-volume queues can be partitioned across Redis clusters based on job type or priority tier.
