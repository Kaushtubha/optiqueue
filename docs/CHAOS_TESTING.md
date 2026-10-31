# Chaos Engineering & Failure Simulation Guide

This guide outlines how to execute controlled fault-injection and chaos experiments against an OptiQueue deployment to validate distributed resilience, data integrity, and automated failover recovery.

---

## 1. Principles of Resilience Verification

OptiQueue is designed to maintain zero job loss and strict idempotency under adverse operating conditions. Chaos testing evaluates whether:
- In-flight jobs are recovered when worker processes crash abruptly.
- Transient network partitions between Redis and the application layer degrade gracefully without deadlocking.
- Retries with jitter protect downstream services during partial outages.

---

## 2. Chaos Scenarios

### Scenario 1: Abrupt Worker Process Termination (`SIGKILL`)

#### Objective
Confirm that jobs claimed by a suddenly killed worker are detected and recovered by surviving nodes.

#### Injection Command
```bash
# Find worker process ID and kill immediately
pkill -9 -f "OptiQueueApplication"
```

#### Expected System Response
1. The killed worker stops publishing heartbeats to Redis key `optiqueue:heartbeat:<workerId>`.
2. After `heartbeat-ttl-seconds` (default 30s), the key expires.
3. The surviving node's `FailoverDetector` runs its scheduled scan:
   - Identifies the unacknowledged `IN_PROGRESS` job.
   - Confirms the worker heartbeat key is missing.
   - Re-sets the job status to `PENDING` and clears `assignedWorkerId`.
4. Job is picked up and processed to completion by a healthy worker node.

---

### Scenario 2: Redis Connectivity Loss Simulation

#### Objective
Verify behavior when the Redis coordination layer becomes temporarily unreachable.

#### Injection Command
Using `iptables` to drop packets on Redis port 6379:
```bash
sudo iptables -A OUTPUT -p tcp --dport 6379 -j DROP
```

#### Expected System Response
1. `DistributedLockService` fails to acquire scheduler leases.
2. In-flight jobs continue local execution.
3. Database fallback prevents duplicate job processing through database-level row locks (`FOR UPDATE SKIP LOCKED`).
4. Once iptables rule is removed, Redis reconnects automatically via Spring Lettuce client.
```bash
sudo iptables -D OUTPUT -p tcp --dport 6379 -j DROP
```

---

### Scenario 3: Downstream Service Failure & Jittered Retry Storm

#### Objective
Verify that cascading downstream 500 errors do not trigger synchronized thundering herd retries.

#### Injection Procedure
1. Submit 500 jobs configured to invoke a mock webhook endpoint returning HTTP `503 Service Unavailable`.
2. Monitor retry timestamps in PostgreSQL:
   ```sql
   SELECT id, retry_count, next_retry_at FROM jobs WHERE status = 'FAILED';
   ```

#### Expected System Response
- `RetryEngine` calculates exponential backoff with full/equal jitter (`calculateEqualJitterBackoffMs`).
- `next_retry_at` timestamps are uniformly distributed across the backoff window rather than clustered at identical milliseconds.
- After 5 unsuccessful attempts, jobs transition to `DEAD_LETTER` with `is_dead_letter = true`.
