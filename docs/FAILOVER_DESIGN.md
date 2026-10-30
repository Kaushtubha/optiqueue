# Distributed Failover & Lease Lock Protocol

This document outlines the failure detection mechanisms, distributed locking implementation, and orphaned job recovery workflow in OptiQueue.

---

## 1. Problem Statement

In distributed task execution architectures, worker nodes may fail abruptly due to:
- Hardware failure or hypervisor preemption
- Out-of-memory errors (OOM crashes)
- Network partitions splitting the worker from the coordinator
- Process freezes (e.g. JVM Stop-The-World garbage collection pauses)

When a node terminates while executing an assigned job, the job must not remain stranded indefinitely in an `IN_PROGRESS` state.

---

## 2. Distributed Locking Protocol

OptiQueue coordinates scheduling and failover tasks using Redis distributed leases (`DistributedLockService`).

### 2.1 Lock Acquisition
- **Mechanism**: Atomic `SET optiqueue:scheduler:lock <holderId> NX PX <ttlMs>`
- **Mutual Exclusion**: Guarantees that only one cluster instance performs global coordination or failover sweeps at any given time.
- **Lease Expiration**: Defaults to `10,000ms` (`DEFAULT_TTL_MS`). If the holder crashes, the lock automatically expires without deadlocking other nodes.

### 2.2 Atomic Release via Lua Script
To prevent a slow node from releasing a lock that has expired and been acquired by another node, release verification is executed via an atomic Lua script:

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

---

## 3. Worker Heartbeat Service

Each active worker instance is assigned a unique identifier (`workerId`) and actively reports liveness:

1. **Heartbeat Publishing**: Every $N$ seconds, active workers record an ephemeral key in Redis:
   `optiqueue:heartbeat:<workerId>` with an expiry TTL (`heartbeat-ttl-seconds`).
2. **Health Verification**: Any node can evaluate worker health by checking key existence in Redis.

---

## 4. Failover Detection & Recovery Protocol

The `FailoverDetector` runs a scheduled background sweep:

```
+-------------------------------------------------------------+
|                 Scheduled Failover Sweep                    |
+-------------------------------------------------------------+
                              |
                              v
              Scan DB for jobs in IN_PROGRESS
              where startedAt < now() - heartbeatTimeout
                              |
                              v
             Is assigned worker heartbeat active?
                              |
               +--------------+--------------+
               |                             |
             [YES]                          [NO]
               |                             |
               v                             v
       Worker is alive;          Worker is presumed dead.
       allow execution           Mark job PENDING / Requeue.
       to continue               Increment attemptCount.
```

### 4.1 Orphaned Job Recovery Steps
1. **Detection**: Select all jobs with `status = 'IN_PROGRESS'` where `startedAt` is older than the configured heartbeat threshold.
2. **Worker Probe**: Check `HeartbeatService.isWorkerAlive(assignedWorkerId)`.
3. **Recovery Action**:
   - If the worker key is expired or missing:
     - Clear the `assignedWorkerId`.
     - Increment `attemptCount`.
     - If `attemptCount` exceeds `maxRetries`, transition job to `DEAD_LETTER`.
     - Otherwise, transition job back to `QUEUED` / `PENDING` for redelivery.
4. **Audit Log**: Emit warning telemetry with details of the orphaned task and recovered execution context.

---

## 5. Network Partition Considerations

During a transient split-brain event:
- A worker partitioned from Redis may continue executing locally.
- To maintain consistency, workers verify their own lock ownership / heartbeat status before committing final results.
- Database unique constraints and status preconditions prevent duplicate final state transitions.
