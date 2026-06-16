-- ═══════════════════════════════════════════════════════════════
-- V1__create_jobs_table.sql
--
-- Flyway naming convention: V{version}__{description}.sql
-- Version numbers must be strictly increasing. Flyway records
-- each migration in a `flyway_schema_history` table so it only
-- runs each file ONCE, even across restarts. This is the contract
-- that makes DB migrations safe in CI/CD pipelines.
--
-- INTERVIEW Q: "Why not use Hibernate's ddl-auto=create-drop?"
-- A: ddl-auto=create-drop destroys your schema on shutdown.
--    ddl-auto=update silently adds columns but never removes them
--    (schema drift). Flyway gives you versioned, auditable, reversible
--    migrations that work the same in dev, staging, and prod.
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS jobs (
    -- UUID primary key: globally unique even across worker nodes.
    -- WHY UUID over BIGSERIAL? No single point of ID generation;
    -- clients can generate the ID before submitting (key to idempotency).
    id                  UUID        PRIMARY KEY,

    -- Human-readable job type tag (e.g., "EMAIL_SEND", "REPORT_GEN")
    job_type            VARCHAR(100) NOT NULL,

    -- JSON payload: flexible, doesn't require schema changes per job type.
    -- In prod you'd use JSONB for indexing; TEXT is fine here.
    payload             TEXT,

    -- Job lifecycle state machine (see JobStatus enum in Step 2)
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING',

    -- Scheduling fields
    priority            INTEGER      NOT NULL DEFAULT 5,   -- Lower = higher priority
    deadline            TIMESTAMP WITH TIME ZONE,          -- NULL = no deadline constraint

    -- Retry tracking
    retry_count         INTEGER      NOT NULL DEFAULT 0,
    max_retries         INTEGER      NOT NULL DEFAULT 5,
    next_retry_at       TIMESTAMP WITH TIME ZONE,
    last_error          TEXT,                              -- Last exception message

    -- Dead-letter flag: set true when retry_count >= max_retries
    is_dead_letter      BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Worker assignment: which worker node picked this job up
    assigned_worker_id  VARCHAR(100),

    -- Idempotency key: client-supplied, unique per logical job.
    -- Indexed because we query by it on every submission.
    idempotency_key     VARCHAR(255) UNIQUE,

    -- Result storage (populated when status = COMPLETED)
    result              TEXT,

    -- Audit timestamps
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE
);

-- ── INDEXES ────────────────────────────────────────────────────
-- The scheduler polls for PENDING jobs ordered by priority, then deadline.
-- This index covers that exact query pattern.
CREATE INDEX IF NOT EXISTS idx_jobs_status_priority
    ON jobs (status, priority, deadline)
    WHERE status = 'PENDING';

-- Worker failover query: find jobs stuck IN_PROGRESS on a dead worker.
CREATE INDEX IF NOT EXISTS idx_jobs_worker_status
    ON jobs (assigned_worker_id, status)
    WHERE status = 'IN_PROGRESS';

-- DLQ query: list all dead-letter jobs for monitoring/manual review.
CREATE INDEX IF NOT EXISTS idx_jobs_dead_letter
    ON jobs (is_dead_letter, created_at)
    WHERE is_dead_letter = TRUE;

-- ── UPDATED_AT TRIGGER ──────────────────────────────────────────
-- Automatically update `updated_at` on every row change.
-- WHY a trigger? JPA's @PreUpdate works, but if anything bypasses
-- JPA (raw SQL, migrations), the timestamp stays stale. DB trigger = bulletproof.
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
