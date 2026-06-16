package com.optiqueue.controller;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * JobResponse — outbound DTO returned by all job endpoints.
 *
 * Contains only what a client needs to see. Internal fields like
 * assignedWorkerId, lastError (only in DLQ view), heap position etc.
 * are intentionally excluded from the standard response.
 *
 * Uses @Builder so JobService can construct it fluently:
 *   JobResponse.from(job) → static factory method below.
 *
 * NOTE: We use Instant for timestamps. The Jackson ObjectMapper
 * (auto-configured by Spring Boot) serializes Instant as an ISO-8601
 * string (e.g., "2026-06-16T13:00:00Z") by default, which is
 * universally parseable by all clients. No timezone confusion.
 */
@Data
@Builder
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
    private String    result;          // Populated only when status=COMPLETED
    private String    lastError;       // Populated only in DLQ view
    private Instant   createdAt;
    private Instant   updatedAt;
    private Instant   startedAt;
    private Instant   completedAt;
    private Instant   nextRetryAt;

    /**
     * Static factory method: maps a Job entity to a JobResponse DTO.
     *
     * WHY static factory over a constructor or MapStruct?
     *   - Constructor: long parameter list = hard to read and maintain
     *   - MapStruct: generates compile-time mapping code; great for large projects
     *     but adds annotation processing complexity we don't need here
     *   - Static factory: readable, co-located with the DTO, easily testable
     *
     * PRODUCTION NOTE: For complex mappings with many DTOs, MapStruct is
     * the right choice. It generates null-safe, type-safe mappers at compile
     * time with zero runtime overhead.
     */
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
