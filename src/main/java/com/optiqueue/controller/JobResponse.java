package com.optiqueue.controller;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * JobResponse — outbound DTO returned by all job endpoints.
 */
public class JobResponse {

    private UUID      id;
    private String    idempotencyKey;
    private String    jobType;
    private String    payload;
    private JobStatus status;
    private int       priority;
    private Instant   deadline;
    private int       retryCount;
    private int       maxRetries;
    private boolean   isDeadLetter;
    private String    result;
    private String    lastError;
    private Instant   createdAt;
    private Instant   updatedAt;
    private Instant   startedAt;
    private Instant   completedAt;
    private Instant   nextRetryAt;

    public JobResponse() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public boolean isDeadLetter() { return isDeadLetter; }
    public void setDeadLetter(boolean deadLetter) { isDeadLetter = deadLetter; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public static class Builder {
        private final JobResponse res = new JobResponse();

        public Builder id(UUID id) { res.id = id; return this; }
        public Builder idempotencyKey(String idempotencyKey) { res.idempotencyKey = idempotencyKey; return this; }
        public Builder jobType(String jobType) { res.jobType = jobType; return this; }
        public Builder payload(String payload) { res.payload = payload; return this; }
        public Builder status(JobStatus status) { res.status = status; return this; }
        public Builder priority(int priority) { res.priority = priority; return this; }
        public Builder deadline(Instant deadline) { res.deadline = deadline; return this; }
        public Builder retryCount(int retryCount) { res.retryCount = retryCount; return this; }
        public Builder maxRetries(int maxRetries) { res.maxRetries = maxRetries; return this; }
        public Builder isDeadLetter(boolean isDeadLetter) { res.isDeadLetter = isDeadLetter; return this; }
        public Builder result(String result) { res.result = result; return this; }
        public Builder lastError(String lastError) { res.lastError = lastError; return this; }
        public Builder createdAt(Instant createdAt) { res.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { res.updatedAt = updatedAt; return this; }
        public Builder startedAt(Instant startedAt) { res.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { res.completedAt = completedAt; return this; }
        public Builder nextRetryAt(Instant nextRetryAt) { res.nextRetryAt = nextRetryAt; return this; }

        public JobResponse build() {
            return res;
        }
    }

    public static JobResponse from(Job job) {
        return JobResponse.builder()
            .id(job.getId())
            .idempotencyKey(job.getIdempotencyKey())
            .jobType(job.getJobType())
            .payload(job.getPayload())
            .status(job.getStatus())
            .priority(job.getPriority())
            .deadline(job.getDeadline())
            .retryCount(job.getRetryCount())
            .maxRetries(job.getMaxRetries())
            .isDeadLetter(job.isDeadLetter())
            .result(job.getResult())
            .lastError(job.getLastError())
            .createdAt(job.getCreatedAt())
            .updatedAt(job.getUpdatedAt())
            .startedAt(job.getStartedAt())
            .completedAt(job.getCompletedAt())
            .nextRetryAt(job.getNextRetryAt())
            .build();
    }
}
