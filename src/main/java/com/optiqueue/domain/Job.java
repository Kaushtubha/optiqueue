package com.optiqueue.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Job — the central entity representing a unit of work in OptiQueue.
 *
 * JPA DESIGN DECISIONS:
 *
 * 1. @Table(name = "jobs") — explicit name avoids dialect-specific quoting issues.
 *    Some DBs (Oracle) uppercase unquoted identifiers. Explicit names = predictable.
 *
 * 2. UUID primary key with @GeneratedValue(strategy = NONE):
 *    WHY NONE? We assign UUIDs ourselves (UUID.randomUUID()) before calling
 *    repository.save(). This lets the client submit a UUID as their idempotency key,
 *    and lets us check for duplicates BEFORE the INSERT, not after.
 *    With GenerationType.AUTO, Hibernate generates the UUID but you lose
 *    client-side ID control.
 *
 * 3. @Enumerated(EnumType.STRING) — stores "PENDING" not "0" in DB.
 *    EnumType.ORDINAL stores the enum's position (0, 1, 2...). If you ever
 *    reorder the enum (adding a state in the middle), all existing rows silently
 *    map to the wrong state. EnumType.STRING is immune to reordering.
 *    This is one of the most dangerous JPA footguns — always use STRING.
 *
 * 4. @Column(nullable = false, updatable = false) on createdAt:
 *    createdAt must never change after INSERT. updatable=false makes Hibernate
 *    exclude it from UPDATE statements. The DB trigger (Step 1 migration) also
 *    prevents changes, giving double protection.
 *
 * 5. No @Version (optimistic locking) yet — we add this in Step 9 when multiple
 *    worker nodes compete to claim the same job. For now, single-node operation.
 *    Production note: without optimistic locking, two workers can claim the same
 *    PENDING job simultaneously → duplicate execution. Step 9 fixes this with
 *    Redis-based distributed locking.
 */
@Entity
@Table(
    name = "jobs",
    indexes = {
        @Index(name = "idx_jobs_status_priority",  columnList = "status, priority, deadline"),
        @Index(name = "idx_jobs_worker_status",    columnList = "assignedWorkerId, status"),
        @Index(name = "idx_jobs_dead_letter",      columnList = "isDeadLetter, createdAt")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    // ── IDENTITY ──────────────────────────────────────────────────────────

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Client-supplied idempotency key. The client picks a unique string
     * (typically a UUID or a hash of the job parameters) before submitting.
     * On re-submission with the same key, we return the existing job
     * instead of creating a duplicate. See Step 8.
     *
     * UNIQUE constraint is on the DB column (declared in migration).
     * We also have a @Column unique=true here so Hibernate knows about it.
     */
    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    // ── JOB DEFINITION ────────────────────────────────────────────────────

    /**
     * Human-readable job type tag. Examples: "EMAIL_SEND", "REPORT_GENERATE".
     * In a production system this would drive which handler class processes the job.
     * Here it's metadata — we simulate all execution with a Thread.sleep.
     */
    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    /**
     * JSON string containing job-specific parameters.
     * WHY TEXT not JSONB? JSONB enables server-side JSON querying (PostgreSQL-specific).
     * TEXT is simpler and portable. In production with heavy JSON querying, use JSONB.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    // ── SCHEDULING ────────────────────────────────────────────────────────

    /**
     * Priority: lower number = higher urgency.
     * 1 = critical, 5 = normal, 10 = background.
     * The custom min-heap (Step 3) orders by this field first,
     * then by deadline for tie-breaking.
     *
     * INTERVIEW Q: "Why lower = higher priority?"
     * A: Min-heap naturally pops the smallest value first. Mapping
     *    priority 1 (critical) to the smallest number means the most
     *    urgent job naturally rises to the top without inversion logic.
     */
    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 5;

    /**
     * Optional absolute deadline. If null, the job has no time constraint.
     * The scheduler uses this as a secondary sort key: among jobs with the
     * same priority, the one with the nearest deadline is processed first
     * (Earliest Deadline First within same priority band).
     */
    @Column(name = "deadline")
    private Instant deadline;

    // ── STATUS ────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    // ── RETRY TRACKING ────────────────────────────────────────────────────

    /**
     * How many times this job has been attempted (including the first attempt).
     * Incremented each time the job transitions FAILED → RETRYING.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * Maximum allowed attempts. Configurable per job.
     * Default pulled from application.yml (optiqueue.retry.max-attempts).
     */
    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    /**
     * When to attempt the next retry. The retry engine checks this timestamp
     * and only re-queues the job once Instant.now() >= nextRetryAt.
     * This is how exponential backoff is implemented — each failure pushes
     * nextRetryAt further into the future.
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * The exception message from the last failure. Stored for debugging.
     * In production you'd store a full stack trace or a correlation ID
     * pointing to a log aggregation system (e.g., Datadog, Splunk).
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    // ── DEAD LETTER ───────────────────────────────────────────────────────

    /**
     * Set to true when retryCount >= maxRetries and status = DEAD_LETTER.
     * Redundant with status == DEAD_LETTER, but useful for partial indexes
     * on the dead_letter queue without filtering on the status enum string.
     */
    @Column(name = "is_dead_letter", nullable = false)
    @Builder.Default
    private boolean isDeadLetter = false;

    // ── WORKER ASSIGNMENT ─────────────────────────────────────────────────

    /**
     * ID of the worker node currently processing this job.
     * Set when status transitions PENDING → IN_PROGRESS.
     * Used by failover logic (Step 10): if a worker's heartbeat goes stale,
     * the scheduler finds all IN_PROGRESS jobs assigned to that worker
     * and reassigns them.
     */
    @Column(name = "assigned_worker_id", length = 100)
    private String assignedWorkerId;

    // ── RESULT ────────────────────────────────────────────────────────────

    /**
     * JSON string containing the job's output, populated on COMPLETED.
     * Retrievable via GET /api/jobs/{id}/result (Step 6).
     */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    // ── AUDIT TIMESTAMPS ──────────────────────────────────────────────────

    /**
     * Set once at INSERT time via @PrePersist. Never updated.
     * updatable=false makes Hibernate omit this from UPDATE SQL.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── JPA LIFECYCLE HOOKS ───────────────────────────────────────────────

    /**
     * @PrePersist runs just before Hibernate executes the INSERT statement.
     * We assign createdAt and updatedAt here because:
     *   1. The DB trigger also sets updatedAt on UPDATE — belt and suspenders.
     *   2. We need createdAt in the application layer immediately after save()
     *      without a separate SELECT. If we let the DB set it, we'd need to
     *      call entityManager.refresh() to see the DB-generated value.
     *
     * We also generate the UUID here if it wasn't set by the caller.
     * This makes Job construction safer — forgetting to set an ID won't cause
     * a null PK exception; we auto-generate one.
     */
    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * @PreUpdate runs just before Hibernate executes any UPDATE statement.
     * Keeps updatedAt current at the application layer (the DB trigger
     * also handles this, but this ensures it's accurate in the in-memory
     * entity before the next read).
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── CONVENIENCE METHODS ───────────────────────────────────────────────

    /**
     * Transitions the job to a new status after validating the move is legal.
     * Centralizing status changes here means no caller can bypass validation.
     *
     * @throws IllegalStateException if the transition is not permitted
     */
    public void transitionTo(JobStatus newStatus) {
        if (!JobStatus.isValidTransition(this.status, newStatus)) {
            throw new IllegalStateException(
                String.format("Illegal job status transition: %s → %s (jobId=%s)",
                    this.status, newStatus, this.id)
            );
        }
        this.status = newStatus;
    }

    /**
     * Marks this job as claimed by a worker.
     * Sets status → IN_PROGRESS, records worker ID and start time.
     */
    public void claimBy(String workerId) {
        transitionTo(JobStatus.IN_PROGRESS);
        this.assignedWorkerId = workerId;
        this.startedAt = Instant.now();
    }

    /**
     * Marks job as successfully completed.
     */
    public void complete(String result) {
        transitionTo(JobStatus.COMPLETED);
        this.result = result;
        this.completedAt = Instant.now();
    }

    /**
     * Records a failure and decides: retry or dead-letter?
     * Calculates the next retry time using exponential backoff.
     *
     * @param errorMessage exception message
     * @param initialDelayMs base delay in ms (doubles each attempt)
     */
    public void recordFailure(String errorMessage, long initialDelayMs) {
        this.lastError = errorMessage;
        this.retryCount++;

        if (this.retryCount >= this.maxRetries) {
            // Exhausted all retries → dead-letter queue
            transitionTo(JobStatus.FAILED);  // FAILED first (valid transition)
            this.status = JobStatus.DEAD_LETTER; // direct set — FAILED→DEAD_LETTER is valid
            this.isDeadLetter = true;
        } else {
            // Schedule a retry with exponential backoff
            // Formula: delay = initialDelay * 2^(retryCount-1)
            // Attempt 1: 1s, Attempt 2: 2s, Attempt 3: 4s, Attempt 4: 8s, Attempt 5: 16s
            long delayMs = initialDelayMs * (1L << (this.retryCount - 1));
            this.nextRetryAt = Instant.now().plusMillis(delayMs);
            transitionTo(JobStatus.FAILED);
            // Retry engine will poll and move to RETRYING → PENDING
        }
    }

    /**
     * Checks if this job is ready to be retried right now.
     */
    public boolean isReadyForRetry() {
        return this.status == JobStatus.FAILED
            && this.retryCount < this.maxRetries
            && this.nextRetryAt != null
            && Instant.now().isAfter(this.nextRetryAt);
    }
}
