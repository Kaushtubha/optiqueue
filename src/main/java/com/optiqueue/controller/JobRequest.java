package com.optiqueue.controller;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * JobRequest — inbound DTO for job submission (POST /api/jobs).
 *
 * WHY a DTO and not the Job entity directly?
 *   - Job entity has server-side fields (id, status, retryCount etc.) that
 *     clients must NOT set. Accepting the entity would let a client submit
 *     {status: "COMPLETED"} and bypass all business logic.
 *   - @Valid + constraint annotations on DTOs give automatic 400 responses
 *     for malformed input — no manual null-checks in the controller.
 *   - DTO fields map to what a client actually understands, not DB internals.
 *
 * VALIDATION ANNOTATIONS (from spring-boot-starter-validation / Jakarta EE):
 *   @NotBlank  — not null AND not empty AND not whitespace-only
 *   @Size      — length constraints
 *   @Min/@Max  — numeric range
 *   These trigger when the controller uses @Valid on the @RequestBody.
 *   Violations throw MethodArgumentNotValidException → 400 response.
 */
@Data
public class JobRequest {

    /**
     * Client-supplied idempotency key.
     * The client generates this (UUID, hash, etc.) and keeps it.
     * Re-submitting with the same key returns the existing job instead of
     * creating a duplicate. See JobService.submitJob() for the check.
     *
     * Optional: if not provided, server generates a UUID (no idempotency guarantee).
     */
    @Size(max = 255, message = "Idempotency key must be at most 255 characters")
    private String idempotencyKey;

    /**
     * Job type tag — drives which handler would process this job.
     * Examples: "EMAIL_SEND", "REPORT_GENERATE", "DATA_EXPORT"
     */
    @NotBlank(message = "jobType is required")
    @Size(max = 100, message = "jobType must be at most 100 characters")
    private String jobType;

    /**
     * JSON payload — job-specific parameters.
     * Example: {"to": "user@example.com", "subject": "Welcome"}
     */
    private String payload;

    /**
     * Priority: 1 (critical) to 10 (background). Default 5 (normal).
     * The custom min-heap orders jobs by this value — lower = more urgent.
     */
    @Min(value = 1, message = "Priority must be between 1 and 10")
    @Max(value = 10, message = "Priority must be between 1 and 10")
    private int priority = 5;

    /**
     * Optional deadline as epoch milliseconds (UTC).
     * null means no deadline constraint.
     * Used for EDF scheduling within same priority band.
     */
    private Long deadlineEpochMs;

    /**
     * Max retry attempts for this specific job.
     * Overrides the global default from application.yml if provided.
     */
    @Min(value = 0, message = "maxRetries cannot be negative")
    @Max(value = 20, message = "maxRetries cannot exceed 20")
    private Integer maxRetries;
}
