# OptiQueue Operations & Incident Response Runbook

This runbook outlines standard operating procedures (SOP), diagnosis steps, and remediation playbooks for operations and on-call engineers supporting OptiQueue in production.

---

## 1. Incident Severity Definitions

- **SEV-1 (Critical Outage)**: System completely unable to process incoming jobs; Redis cluster or primary database unreachable.
- **SEV-2 (Degraded Performance)**: Significant processing backlog accumulation; dead-letter queue spike (>5% failure rate); elevated p99 latency (>2s).
- **SEV-3 (Minor / Impaired)**: Isolated task handler failure (e.g. external webhook provider unreachable); non-impacting worker heartbeat blips.

---

## 2. Common Scenarios & Remediation Playbooks

### Playbook A: High Dead-Letter Queue (DLQ) Accumulation

#### Symptoms
- Alert: `OptiQueueHighDeadLetterRate` firing.
- Metric `deadLetterCount` growing rapidly.

#### Triage Steps
1. Query recent dead-lettered jobs via API:
   ```bash
   curl -H "X-API-KEY: $API_KEY" http://localhost:8080/api/jobs/dead-letter
   ```
2. Inspect `lastFailureReason` in the response payloads to identify failure classification (e.g., downstream 5xx, JSON parsing error, authentication failure).
3. If failure is due to a transient downstream outage that has been resolved, re-queue dead-letter jobs:
   ```bash
   curl -X POST -H "X-API-KEY: $API_KEY" http://localhost:8080/api/jobs/dead-letter/{jobId}/retry
   ```

---

### Playbook B: Queue Backlog & Worker Starvation

#### Symptoms
- Queue depth steadily increases while `runningCount` remains pegged at maximum capacity.
- Job completion latency spikes.

#### Triage Steps
1. Check active worker utilization via telemetry:
   ```bash
   curl -H "X-API-KEY: $API_KEY" http://localhost:8080/api/jobs/metrics
   ```
2. Verify thread pool status in application logs:
   Search for `WorkerPool` or `JobBlockingQueue` full warnings.
3. Remediation:
   - Temporarily scale out application instances horizontally.
   - Adjust `optiqueue.worker.max-pool-size` in environment variables:
     ```bash
     OPTIQUEUE_WORKER_MAX_POOL_SIZE=32
     ```
   - Restart or trigger dynamic configuration reload.

---

### Playbook C: Redis Connectivity Issues

#### Symptoms
- Logs indicate `RedisConnectionFailureException` or `CommandTimeoutException`.
- Distributed lock acquisition fails repeatedly.

#### Triage Steps
1. Test Redis connectivity from the host container:
   ```bash
   redis-cli -h $REDIS_HOST -p $REDIS_PORT ping
   ```
2. Check Redis memory usage and CPU saturation:
   ```bash
   redis-cli info memory
   redis-cli info stats
   ```
3. Verify TLS/SSL settings match cloud provider requirements (`REDIS_SSL=true`).

---

### Playbook D: Database Connection Pool Exhaustion

#### Symptoms
- HikariCP pool exhaustion messages: `Connection is not available, request timed out after 30000ms`.

#### Triage Steps
1. Verify active database connections:
   ```sql
   SELECT count(*), state FROM pg_stat_activity WHERE datname = 'optiqueue' GROUP BY state;
   ```
2. Check for long-running transactions holding locks on the `jobs` table:
   ```sql
   SELECT pid, query_start, age(now(), query_start), query 
   FROM pg_stat_activity 
   WHERE state != 'idle' AND datname = 'optiqueue';
   ```
3. Terminate stuck transactions if necessary:
   ```sql
   SELECT pg_terminate_backend(<pid>);
   ```

---

## 3. Routine Health Audits

1. **Daily**: Review error rate and dead-letter count in Grafana / Dashboard.
2. **Weekly**: Review slow job handlers and adjust timeout configurations.
3. **Monthly**: Prune archived job historical records older than retention policy (default 30 days).
