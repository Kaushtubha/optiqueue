package com.optiqueue.controller;

import jakarta.validation.constraints.*;

/**
 * JobRequest — inbound DTO for job submission (POST /api/jobs).
 */
public class JobRequest {

    @Size(max = 255, message = "Idempotency key must be at most 255 characters")
    private String idempotencyKey;

    @NotBlank(message = "jobType is required")
    @Size(max = 100, message = "jobType must be at most 100 characters")
    private String jobType;

    private String payload;

    @Min(value = 1, message = "Priority must be between 1 and 10")
    @Max(value = 10, message = "Priority must be between 1 and 10")
    private int priority = 5;

    private Long deadlineEpochMs;

    @Min(value = 0, message = "maxRetries cannot be negative")
    @Max(value = 20, message = "maxRetries cannot exceed 20")
    private Integer maxRetries;

    public JobRequest() {
    }

    public JobRequest(String idempotencyKey, String jobType, String payload, int priority, Long deadlineEpochMs, Integer maxRetries) {
        this.idempotencyKey = idempotencyKey;
        this.jobType = jobType;
        this.payload = payload;
        this.priority = priority;
        this.deadlineEpochMs = deadlineEpochMs;
        this.maxRetries = maxRetries;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Long getDeadlineEpochMs() {
        return deadlineEpochMs;
    }

    public void setDeadlineEpochMs(Long deadlineEpochMs) {
        this.deadlineEpochMs = deadlineEpochMs;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }
}
