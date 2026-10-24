package com.optiqueue.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Job — the central entity representing a unit of work in OptiQueue.
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
public class Job {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "priority", nullable = false)
    private int priority = 5;

    @Column(name = "deadline")
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "is_dead_letter", nullable = false)
    private boolean isDeadLetter = false;

    @Column(name = "assigned_worker_id", length = 100)
    private String assignedWorkerId;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Job() {
    }

    public static Builder builder() {
        return new Builder();
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void transitionTo(JobStatus newStatus) {
        if (!JobStatus.isValidTransition(this.status, newStatus)) {
            throw new IllegalStateException(
                String.format("Illegal job status transition: %s → %s (jobId=%s)",
                    this.status, newStatus, this.id)
            );
        }
        this.status = newStatus;
    }

    public void claimBy(String workerId) {
        transitionTo(JobStatus.IN_PROGRESS);
        this.assignedWorkerId = workerId;
        this.startedAt = Instant.now();
    }

    public void complete(String result) {
        transitionTo(JobStatus.COMPLETED);
        this.result = result;
        this.completedAt = Instant.now();
    }

    public void recordFailure(String errorMessage, long initialDelayMs) {
        recordFailure(errorMessage, initialDelayMs, false);
    }

    public void recordFailure(String errorMessage, long initialDelayMs, boolean useJitter) {
        this.lastError = errorMessage;
        this.retryCount++;

        if (this.retryCount >= this.maxRetries) {
            transitionTo(JobStatus.FAILED);
            transitionTo(JobStatus.DEAD_LETTER);
            this.isDeadLetter = true;
        } else {
            long delayMs = useJitter
                ? com.optiqueue.retry.RetryEngine.calculateEqualJitterBackoffMs(this.retryCount, initialDelayMs)
                : initialDelayMs * (1L << (this.retryCount - 1));
            this.nextRetryAt = Instant.now().plusMillis(delayMs);
            transitionTo(JobStatus.FAILED);
        }
    }

    public boolean isReadyForRetry() {
        return this.status == JobStatus.FAILED
            && this.retryCount < this.maxRetries
            && this.nextRetryAt != null
            && Instant.now().isAfter(this.nextRetryAt);
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public boolean isDeadLetter() { return isDeadLetter; }
    public void setDeadLetter(boolean deadLetter) { isDeadLetter = deadLetter; }

    public String getAssignedWorkerId() { return assignedWorkerId; }
    public void setAssignedWorkerId(String assignedWorkerId) { this.assignedWorkerId = assignedWorkerId; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    // Builder
    public static class Builder {
        private final Job job = new Job();

        public Builder id(UUID id) { job.id = id; return this; }
        public Builder idempotencyKey(String idempotencyKey) { job.idempotencyKey = idempotencyKey; return this; }
        public Builder jobType(String jobType) { job.jobType = jobType; return this; }
        public Builder payload(String payload) { job.payload = payload; return this; }
        public Builder priority(int priority) { job.priority = priority; return this; }
        public Builder deadline(Instant deadline) { job.deadline = deadline; return this; }
        public Builder status(JobStatus status) { job.status = status; return this; }
        public Builder retryCount(int retryCount) { job.retryCount = retryCount; return this; }
        public Builder maxRetries(int maxRetries) { job.maxRetries = maxRetries; return this; }
        public Builder nextRetryAt(Instant nextRetryAt) { job.nextRetryAt = nextRetryAt; return this; }
        public Builder lastError(String lastError) { job.lastError = lastError; return this; }
        public Builder isDeadLetter(boolean isDeadLetter) { job.isDeadLetter = isDeadLetter; return this; }
        public Builder assignedWorkerId(String assignedWorkerId) { job.assignedWorkerId = assignedWorkerId; return this; }
        public Builder result(String result) { job.result = result; return this; }
        public Builder createdAt(Instant createdAt) { job.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { job.updatedAt = updatedAt; return this; }
        public Builder startedAt(Instant startedAt) { job.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { job.completedAt = completedAt; return this; }

        public Job build() {
            return job;
        }
    }
}
