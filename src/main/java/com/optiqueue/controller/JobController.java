package com.optiqueue.controller;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.redis.RedisRateLimiter;
import com.optiqueue.service.JobService;
import com.optiqueue.service.JobService.CancelResult;
import com.optiqueue.service.JobService.JobSubmitResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JobController — REST API entry point for OptiQueue.
 *
 * @RestController = @Controller + @ResponseBody:
 *   Every method return value is serialized to JSON automatically.
 *   No need for @ResponseBody on each method.
 *
 * @RequestMapping("/api/jobs"):
 *   All endpoints share this prefix. If we ever version the API
 *   (/api/v2/jobs), we change it in one place.
 *
 * CONTROLLER RESPONSIBILITY:
 *   ONLY: parse request, call service, map result to HTTP response.
 *   NEVER: business logic, direct DB access, retry decisions.
 *   Business logic belongs in JobService. This keeps controllers
 *   thin and testable via MockMvc without starting a full Spring context.
 *
 * INTERVIEW Q: "Why separate Controller, Service, Repository layers?"
 * A: Single Responsibility Principle. Each layer has one reason to change:
 *   - Controller changes if HTTP protocol changes (REST → GraphQL)
 *   - Service changes if business rules change
 *   - Repository changes if storage technology changes (Postgres → MongoDB)
 *   Mixing them creates a God class that breaks for any reason.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Slf4j
public class JobController {

    private final JobService       jobService;
    private final RedisRateLimiter rateLimiter;

    // ── POST /api/jobs — Submit a job ─────────────────────────────────────

    /**
     * Submit a new job for processing.
     *
     * @Valid: triggers validation annotations on JobRequest.
     *   If any constraint fails, Spring throws MethodArgumentNotValidException
     *   → our GlobalExceptionHandler converts it to 400 with error details.
     *
     * Returns:
     *   201 Created  — new job was created
     *   200 OK       — job already exists (idempotent re-submission)
     *
     * Example curl:
     *   curl -X POST http://localhost:8080/api/jobs \
     *     -H "Content-Type: application/json" \
     *     -d '{"jobType":"EMAIL_SEND","payload":"{\"to\":\"user@example.com\"}",
     *           "priority":3,"idempotencyKey":"my-unique-key-001"}'
     */
    @PostMapping
    public ResponseEntity<JobResponse> submitJob(@Valid @RequestBody JobRequest request,
                                                  HttpServletRequest httpRequest) {
        // Rate limit by client IP — 100 job submissions per minute per IP
        String clientIp = getClientIp(httpRequest);
        RedisRateLimiter.RateLimitResult limit = rateLimiter.checkLimit(clientIp, 100, 60);

        if (!limit.allowed()) {
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .header("X-RateLimit-Limit",     String.valueOf(100))
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset",     String.valueOf(System.currentTimeMillis() / 1000 + 60))
                .build();
        }

        JobSubmitResult result = jobService.submitJob(request);
        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .header("X-RateLimit-Remaining", String.valueOf(limit.remaining()))
            .body(result.response());
    }

    // ── GET /api/jobs/{id} — Get job status ───────────────────────────────

    /**
     * Returns the current status and metadata of a job.
     *
     * Returns:
     *   200 OK    — job found
     *   404 Not Found — no job with this ID
     *
     * Example curl:
     *   curl http://localhost:8080/api/jobs/550e8400-e29b-41d4-a716-446655440000
     */
    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID id) {
        return jobService.getJob(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ── GET /api/jobs/{id}/result — Get job result ────────────────────────

    /**
     * Returns the result of a completed job.
     *
     * Returns:
     *   200 OK          — job completed, result attached
     *   204 No Content  — job exists but not yet completed (still running/pending)
     *   404 Not Found   — no job with this ID
     *
     * The 204 vs 200 distinction lets clients poll for results:
     *   while (true) {
     *     response = GET /api/jobs/{id}/result
     *     if (response.status == 200) break;       // Done
     *     if (response.status == 204) sleep(1000); // Still processing
     *     if (response.status == 404) error();     // Wrong ID
     *   }
     *
     * PRODUCTION NOTE: For long-running jobs, polling is acceptable for simple
     * cases but wasteful at scale. Production systems use:
     *   - Webhooks: server calls your callback URL when done
     *   - SSE (Server-Sent Events): server pushes status updates to the client
     *   - WebSockets: bidirectional real-time channel
     *
     * Example curl:
     *   curl http://localhost:8080/api/jobs/550e8400-e29b-41d4-a716-446655440000/result
     */
    @GetMapping("/{id}/result")
    public ResponseEntity<Map<String, Object>> getJobResult(@PathVariable UUID id) {
        Optional<Job> jobOpt = jobService.getJobForResult(id);

        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Job job = jobOpt.get();

        if (job.getStatus() != JobStatus.COMPLETED) {
            // Job exists but isn't done yet — 204 No Content
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(Map.of(
            "jobId",       job.getId(),
            "status",      job.getStatus(),
            "result",      job.getResult() != null ? job.getResult() : "",
            "completedAt", job.getCompletedAt()
        ));
    }

    // ── DELETE /api/jobs/{id} — Cancel a job ──────────────────────────────

    /**
     * Cancels a job if it hasn't already finished.
     *
     * Returns:
     *   200 OK           — job cancelled successfully
     *   404 Not Found    — no job with this ID
     *   409 Conflict     — job is in a terminal state (COMPLETED or DEAD_LETTER)
     *                      Can't cancel what's already done.
     *
     * WHY DELETE for cancel?
     *   PATCH /api/jobs/{id}/cancel is semantically correct too.
     *   DELETE is chosen here because it's conceptually "remove from processing".
     *   In practice, both are used — document your choice and be consistent.
     *
     * Example curl:
     *   curl -X DELETE http://localhost:8080/api/jobs/550e8400-e29b-41d4-a716-446655440000
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> cancelJob(@PathVariable UUID id) {
        CancelResult result = jobService.cancelJob(id);

        return switch (result) {
            case CANCELLED ->
                ResponseEntity.ok(Map.of("message", "Job cancelled successfully", "jobId", id.toString()));

            case NOT_FOUND ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found", "jobId", id.toString()));

            case ALREADY_CANCELLED ->
                ResponseEntity.ok(Map.of("message", "Job was already cancelled", "jobId", id.toString()));

            case TERMINAL_STATE ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot cancel a job in terminal state (COMPLETED or DEAD_LETTER)",
                                 "jobId", id.toString()));
        };
    }

    // ── GET /api/jobs/dead-letter — List DLQ jobs ─────────────────────────

    /**
     * Returns all jobs in the dead-letter queue (exhausted all retries).
     *
     * In production, this endpoint would be secured (admin only).
     * Dead-letter jobs need manual inspection and re-queueing.
     *
     * Example curl:
     *   curl http://localhost:8080/api/jobs/dead-letter
     */
    @GetMapping("/dead-letter")
    public ResponseEntity<List<JobResponse>> getDeadLetterJobs() {
        return ResponseEntity.ok(jobService.getDeadLetterJobs());
    }

    // ── GET /api/jobs?status=PENDING — List by status ─────────────────────

    /**
     * Lists jobs filtered by status. Useful for monitoring dashboards.
     *
     * PRODUCTION NOTE: This would need pagination (@PageableDefault + Page<JobResponse>)
     * for any real workload. SELECT * with no LIMIT on a million-row table = OOM.
     * We skip pagination here to keep Step 6 focused.
     *
     * Example curl:
     *   curl "http://localhost:8080/api/jobs?status=FAILED"
     */
    @GetMapping
    public ResponseEntity<?> listJobs(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String idempotencyKey) {

        // Lookup by idempotency key — the client's primary way to re-find their job
        // without needing to persist the server-generated UUID
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return jobService.findByIdempotencyKey(idempotencyKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        }

        List<JobResponse> jobs = (status != null)
            ? jobService.getJobsByStatus(status)
            : List.of();

        return ResponseEntity.ok(jobs);
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────

    /**
     * Extracts the real client IP address.
     * Checks X-Forwarded-For first — set by reverse proxies (nginx, ALB, Cloudflare).
     * Falls back to the direct remote address (works without a proxy).
     *
     * PRODUCTION NOTE: X-Forwarded-For can be spoofed by clients.
     * In production, only trust this header if it comes from a known proxy.
     * Set nginx/ALB to overwrite (not append) this header.
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // First IP in the chain is the original client
        }
        return request.getRemoteAddr();
    }
}
