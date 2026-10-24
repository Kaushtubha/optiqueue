package com.optiqueue.repository;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JobRepository — Spring Data JPA repository for the jobs table.
 *
 * Spring Data auto-generates the implementation at runtime by reading
 * method names and @Query annotations. No boilerplate DAO class needed.
 *
 * WHAT SPRING DATA GIVES US FOR FREE (from JpaRepository<Job, UUID>):
 *   save(job)           → INSERT or UPDATE (checks if ID already exists)
 *   findById(id)        → SELECT by PK
 *   findAll()           → SELECT all (careful in production — always paginate)
 *   delete(job)         → DELETE
 *   count()             → SELECT COUNT(*)
 *
 * We add custom queries for the three critical access patterns:
 *   1. Scheduler: fetch PENDING jobs ordered by priority + deadline
 *   2. Retry engine: fetch FAILED jobs whose next_retry_at has passed
 *   3. Failover: fetch IN_PROGRESS jobs assigned to a specific (dead) worker
 */
@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    // ── IDEMPOTENCY LOOKUP ────────────────────────────────────────────────

    /**
     * Used in Step 8: before creating a new job, check if a job with this
     * idempotency key already exists. If yes, return it instead of inserting.
     *
     * Spring Data derives the SQL from the method name:
     *   findBy + IdempotencyKey → SELECT * FROM jobs WHERE idempotency_key = ?
     */
    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    // ── SCHEDULER QUERY ───────────────────────────────────────────────────

    /**
     * Fetches the top N PENDING jobs ordered by scheduling priority.
     *
     * Ordering logic:
     *   1. priority ASC  — lower number = more urgent (1 beats 5)
     *   2. deadline ASC NULLS LAST — among same-priority, earliest deadline first
     *   3. createdAt ASC — among same priority+deadline, FIFO (submission order)
     *
     * This is an EDF (Earliest Deadline First) hybrid:
     *   - Primary sort: explicit priority band
     *   - Secondary sort: EDF within the same priority band
     *
     * WHY JPQL not SQL?
     *   JPQL operates on entity field names (j.priority), not column names (priority).
     *   It's portable across databases. SQL is fine too, but JPQL keeps the
     *   abstraction layer consistent.
     *
     * WHY LIMIT via setMaxResults / Spring's LIMIT instead of subquery?
     *   The scheduler loads only as many jobs as it can process immediately
     *   (e.g., equal to the worker pool size). Loading all PENDING jobs into
     *   memory on every poll would be catastrophic at scale.
     *
     * INTERVIEW Q: "What's the time complexity of this query?"
     * A: O(log N) for index scan on (status, priority, deadline) + O(K) for
     *    fetching K results. The partial index in V1 migration covers this exact
     *    query pattern. Without the index, it degrades to O(N) table scan.
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.status = :status
        ORDER BY j.priority ASC, j.deadline ASC NULLS LAST, j.createdAt ASC
        LIMIT :limit
        """)
    List<Job> findTopNByStatusOrderByPriorityAndDeadline(
        @Param("status") JobStatus status,
        @Param("limit") int limit
    );

    // ── RETRY ENGINE QUERY ────────────────────────────────────────────────

    /**
     * Fetches all FAILED jobs that are ready for retry right now.
     *
     * A job is "ready for retry" if:
     *   - status = FAILED (not yet re-queued as RETRYING)
     *   - retryCount < maxRetries (still has attempts left)
     *   - nextRetryAt <= now (the backoff delay has elapsed)
     *
     * The retry engine (Step 7) runs this query on a @Scheduled interval,
     * then moves each matching job back to PENDING.
     *
     * INTERVIEW Q: "Why FAILED and not RETRYING?"
     * A: RETRYING is a transient state used only to signal "this job is
     *    being moved back to PENDING." The DB stores FAILED + nextRetryAt.
     *    RETRYING is set, then immediately followed by setting PENDING in
     *    the same transaction. It's for external observability (monitoring
     *    dashboards can show "X jobs are in retry backoff").
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.status = 'FAILED'
          AND j.retryCount < j.maxRetries
          AND j.nextRetryAt <= :now
        """)
    List<Job> findJobsReadyForRetry(@Param("now") Instant now);

    /**
     * Finds FAILED jobs that have exhausted their retry limit and should be moved to DEAD_LETTER.
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.status = 'FAILED'
          AND j.retryCount >= j.maxRetries
        """)
    List<Job> findExhaustedFailedJobs();

    // ── FAILOVER QUERY ────────────────────────────────────────────────────

    /**
     * Fetches all IN_PROGRESS jobs assigned to a specific worker.
     *
     * Used in Step 10 (worker failover): when the heartbeat monitor detects
     * that a worker hasn't sent a heartbeat in >TTL seconds, it calls this
     * to find all jobs that worker was running, then reassigns them.
     *
     * INTERVIEW Q: "How do you handle a worker that crashes mid-job?"
     * A: The heartbeat monitor (Step 10) runs on the scheduler node. Every
     *    HEARTBEAT_TTL_MS, it checks Redis for each known worker's heartbeat
     *    key. If the key has expired (worker dead), it runs this query,
     *    resets those jobs back to PENDING, and they're picked up by healthy
     *    workers on the next scheduler poll.
     *
     * PRODUCTION NOTE: In a real distributed system, this reassignment itself
     * needs a distributed lock so two scheduler nodes don't both reassign the
     * same job. Here, single-scheduler — no contention.
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.assignedWorkerId = :workerId
          AND j.status = 'IN_PROGRESS'
        """)
    List<Job> findInProgressJobsByWorker(@Param("workerId") String workerId);

    // ── DEAD LETTER QUEUE QUERY ───────────────────────────────────────────

    /**
     * Returns all dead-letter jobs, newest first.
     * Used by the GET /api/jobs/dead-letter endpoint (Step 6).
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.isDeadLetter = true
        ORDER BY j.updatedAt DESC
        """)
    List<Job> findDeadLetterJobs();

    // ── FAILOVER: STUCK JOBS ──────────────────────────────────────────────

    /**
     * Finds all IN_PROGRESS jobs that started before a given cutoff time.
     *
     * Used by FailoverDetector: jobs started before (now - heartbeatTtlMs)
     * are candidates for orphan recovery — their worker may be dead.
     *
     * The caller then verifies via Redis heartbeat before actually recovering.
     * This two-step approach avoids resetting jobs that are still running
     * on a slow-but-alive worker.
     *
     * @param stuckBefore  cutoff time — jobs started before this are checked
     */
    @Query("""
        SELECT j FROM Job j
        WHERE j.status = 'IN_PROGRESS'
          AND j.startedAt < :stuckBefore
        ORDER BY j.startedAt ASC
        """)
    List<Job> findStuckInProgressJobs(@Param("stuckBefore") Instant stuckBefore);

    // ── STATUS QUERIES ────────────────────────────────────────────────────

    /**
     * Returns all jobs with a given status. Used for monitoring endpoints.
     * Spring Data derives this from the method name automatically.
     */
    List<Job> findByStatus(JobStatus status);

    /**
     * Bulk status transition: resets all IN_PROGRESS jobs on a dead worker
     * back to PENDING in a single UPDATE statement (more efficient than
     * loading entities, mutating, and saving one-by-one).
     *
     * @Modifying marks this as a write operation (required for UPDATE/DELETE).
     * clearAutomatically = true: clears the JPA first-level cache after the
     * bulk update, so subsequent reads see the updated state from DB, not
     * the stale cached version.
     *
     * INTERVIEW Q: "What's the JPA first-level cache and why does it matter here?"
     * A: JPA's EntityManager caches entity instances for the duration of a
     *    transaction. A JPQL bulk UPDATE bypasses this cache — it goes straight
     *    to SQL. If you then call findById(), JPA returns the stale cached
     *    entity, not the DB-updated one. clearAutomatically=true evicts the
     *    cache so the next read hits the DB.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Job j
        SET j.status = 'PENDING',
            j.assignedWorkerId = null,
            j.startedAt = null
        WHERE j.assignedWorkerId = :workerId
          AND j.status = 'IN_PROGRESS'
        """)
    int reassignJobsFromDeadWorker(@Param("workerId") String workerId);

    // ── DASHBOARD & METRICS QUERIES ───────────────────────────────────────

    /**
     * Counts the total number of jobs currently in a given status.
     */
    long countByStatus(JobStatus status);

    /**
     * Retrieves the 50 most recently updated jobs for the live dashboard.
     */
    List<Job> findTop50ByOrderByUpdatedAtDesc();
}
