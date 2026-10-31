# OptiQueue Database Schema & Indexing Architecture

This document describes the PostgreSQL relational schema, column semantics, index designs, and query execution plans for OptiQueue.

---

## 1. Relational Table: `jobs`

The `jobs` table is the authoritative persistent store for all background units of work.

```sql
CREATE TABLE jobs (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(255) UNIQUE,
    job_type            VARCHAR(100) NOT NULL,
    payload             TEXT,
    priority            INTEGER NOT NULL DEFAULT 5,
    deadline            TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count         INTEGER NOT NULL DEFAULT 0,
    max_retries         INTEGER NOT NULL DEFAULT 5,
    next_retry_at       TIMESTAMP WITH TIME ZONE,
    last_error          TEXT,
    is_dead_letter      BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_worker_id  VARCHAR(100),
    result              TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE
);
```

---

## 2. Indexing Strategy

OptiQueue uses targeted composite indexes to guarantee sub-millisecond query execution under heavy concurrent read/write loads:

### 2.1 Scheduler Polling Index
```sql
CREATE INDEX idx_jobs_status_priority ON jobs (status, priority, deadline);
```
- **Purpose**: Powers `SchedulerService` batch queries fetching the highest-priority `PENDING` jobs.
- **Access Pattern**: B-tree index scan on `status = 'PENDING'`, sorted ascending by `priority` (lower numbers first), then ascending by `deadline`.
- **Query**:
  ```sql
  SELECT * FROM jobs 
  WHERE status = 'PENDING' 
  ORDER BY priority ASC, deadline ASC 
  LIMIT 32;
  ```

### 2.2 Worker Heartbeat & Failover Index
```sql
CREATE INDEX idx_jobs_worker_status ON jobs (assigned_worker_id, status);
```
- **Purpose**: Powers `FailoverDetector` scans when recovering orphaned jobs from dead worker threads.
- **Access Pattern**: Identifies in-flight jobs (`status = 'IN_PROGRESS'`) assigned to unverified worker identifiers.

### 2.3 Dead-Letter Queue Index
```sql
CREATE INDEX idx_jobs_dead_letter ON jobs (is_dead_letter, created_at);
```
- **Purpose**: Accelerates administrative dashboard and API queries retrieving all exhausted/dead-lettered jobs in chronological order.

### 2.4 Idempotency Index
```sql
ALTER TABLE jobs ADD CONSTRAINT uk_jobs_idempotency UNIQUE (idempotency_key);
```
- **Purpose**: Enforces atomic database-level deduplication. If duplicate job creation requests occur simultaneously, the second transaction fails immediately on unique violation.

---

## 3. High-Throughput Concurrency Primitives

### 3.1 `SELECT ... FOR UPDATE SKIP LOCKED`
When multi-node instances poll jobs concurrently from PostgreSQL without Redis coordination:
```sql
SELECT * FROM jobs 
WHERE status = 'PENDING' 
ORDER BY priority ASC, deadline ASC 
LIMIT :limit 
FOR UPDATE SKIP LOCKED;
```
- **Advantage**: Prevents row-level lock contention. Worker nodes lock only the rows they intend to process; concurrent workers automatically skip locked rows and claim subsequent records without blocking.

---

## 4. Partitioning & Archiving Strategy

For deployments processing millions of jobs daily:
1. **Range Partitioning**: Partition `jobs` by range of `created_at` (e.g. monthly partitions `jobs_2026_10`, `jobs_2026_11`).
2. **Archiving Policy**:
   - Completed jobs older than 30 days are migrated to `jobs_archive` cold storage or S3 parquet exports.
   - Preserves active table performance and keeps B-tree indexes compact inside PostgreSQL buffer cache.
