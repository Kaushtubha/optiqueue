# Diagnostic Matrix & System Error Codes

This document catalogs error codes, HTTP status mappings, log signature patterns, and diagnostic workflows for OptiQueue.

---

## 1. Error Codes Catalog

| Error Code | HTTP Status | Component | Description |
|---|---|---|---|
| `ERR_JOB_NOT_FOUND` | `404 Not Found` | `JobService` | Requested job UUID does not exist in PostgreSQL |
| `ERR_INVALID_TRANSITION` | `400 / 409 Conflict` | `Job.transitionTo` | Illegal status transition attempted on job state machine |
| `ERR_RATE_LIMIT_EXCEEDED` | `429 Too Many Requests` | `RedisRateLimiter` | Client exceeded allotted submission quota for the time window |
| `ERR_UNAUTHORIZED` | `401 Unauthorized` | `ApiKeyAuthFilter` | Request missing or carrying invalid `X-API-KEY` token |
| `ERR_QUEUE_FULL` | `503 Service Unavailable` | `JobBlockingQueue` | In-memory buffer capacity saturated; backpressure applied |
| `ERR_LOCK_TIMEOUT` | `500 Internal Error` | `DistributedLockService` | Unable to acquire Redis distributed lock within lease timeout |
| `ERR_DLQ_EXHAUSTED` | N/A (Async) | `RetryEngine` | Task exceeded `maxRetries` threshold and moved to Dead-Letter Queue |

---

## 2. Diagnostic & Remediation Matrix

### 2.1 `IllegalStateException: Illegal job status transition`
- **Log Pattern**: `Illegal job status transition: %s -> %s (jobId=%s)`
- **Root Cause**: Concurrent workers attempting conflicting state mutations (e.g. attempting to mark `COMPLETED` a job already marked `CANCELLED`).
- **Remediation**: Normal operational defense. Ensure client applications avoid submitting cancellation requests concurrently with known completion windows.

---

### 2.2 `Redis rate limit check failed ... failing open`
- **Log Pattern**: `Redis rate limit check failed for %s: %s — failing open`
- **Root Cause**: Redis connectivity lost or command timeout exceeded.
- **Remediation**:
  1. Check Redis process status: `systemctl status redis` or `docker compose ps redis`.
  2. Verify network latency between application pods and Redis host.
  3. Note: OptiQueue intentionally fails open to prevent Redis outages from blocking critical background job ingestion.

---

### 2.3 `Blocking queue full at X/Y`
- **Log Pattern**: `Blocking queue full at %d/%d — %d jobs deferred to next tick`
- **Root Cause**: Scheduler polling batch exceeds the consumption rate of worker threads.
- **Remediation**:
  1. Increase worker pool size via `optiqueue.worker.max-pool-size`.
  2. Inspect task handlers for slow external network calls or missing HTTP client timeouts.
  3. Scale horizontal replicas across the cluster.

---

### 2.4 `Heartbeat key expired / missing`
- **Log Pattern**: `Failover scan: recovered orphaned job %s from presumed-dead worker %s`
- **Root Cause**: A worker thread pool crashed (OOM, SIGKILL) or was partitioned.
- **Remediation**:
  1. System automatically reclaims the job and resets status to `PENDING`.
  2. Inspect JVM garbage collection logs (`hs_err_pid.log` or GC pause times) for prolonged Stop-The-World events.
