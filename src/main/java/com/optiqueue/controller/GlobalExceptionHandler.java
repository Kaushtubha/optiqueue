package com.optiqueue.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles @Valid / @Validated failures on request bodies.
     *
     * MethodArgumentNotValidException is thrown by Spring MVC when:
     *   @PostMapping method has @Valid @RequestBody and the body fails constraints.
     *
     * Response format:
     * {
     *   "timestamp": "2026-06-16T13:00:00Z",
     *   "status": 400,
     *   "error": "Validation Failed",
     *   "fieldErrors": {
     *     "jobType": "jobType is required",
     *     "priority": "Priority must be between 1 and 10"
     *   }
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                // If multiple errors on same field, join them
                (msg1, msg2) -> msg1 + "; " + msg2
            ));

        Map<String, Object> body = buildErrorBody(
            HttpStatus.BAD_REQUEST, "Validation Failed", null);
        body.put("fieldErrors", fieldErrors);

        log.warn("Validation failure: {}", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles duplicate idempotency key submissions that slip past the
     * application-level check due to a TOCTOU race condition.
     *
     * When two identical requests arrive simultaneously:
     *   1. Both read: "no job with this key" (Layer 1 check passes for both)
     *   2. Both attempt INSERT
     *   3. First INSERT succeeds (201 Created returned to client A)
     *   4. Second INSERT hits UNIQUE constraint → DataIntegrityViolationException
     *   5. This handler catches it → fetch the existing job → 200 OK to client B
     *
     * Client B gets the same job as client A — correct idempotent behaviour.
     *
     * INTERVIEW Q: "Why not use INSERT ... ON CONFLICT DO NOTHING?"
     * A: PostgreSQL supports upsert via ON CONFLICT, which handles this at the
     *    SQL level without an exception. Spring Data JPA's save() doesn't expose
     *    this directly — you'd need a @Query with native SQL. For clarity and
     *    portability, the exception handling approach is more explicit.
     *    In production at scale, native upsert is more efficient.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(
            org.springframework.dao.DataIntegrityViolationException ex) {

        String message = ex.getMessage() != null ? ex.getMessage() : "";

        // Only handle idempotency_key constraint violations, not other DB constraints
        if (message.contains("idempotency_key") || message.contains("idx_jobs")) {
            log.warn("Duplicate idempotency key rejected (concurrent submission): {}",
                message.substring(0, Math.min(message.length(), 200)));

            Map<String, Object> body = buildErrorBody(HttpStatus.OK,
                "Duplicate submission detected. The existing job has been returned.", null);
            body.put("hint", "Re-fetch the job using GET /api/jobs?idempotencyKey=<your-key>");

            // Return 200 — the idempotency key was valid, we just already have this job
            return ResponseEntity.ok(body);
        }

        // Other constraint violations (e.g., FK violations) → 409 Conflict
        log.error("Database constraint violation: {}", message);
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(buildErrorBody(HttpStatus.CONFLICT, "Database constraint violation", null));
    }

    /**
     * Handles illegal state transitions (from Job.transitionTo()).
     * Returns 409 Conflict — the request is valid but conflicts with current state.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(buildErrorBody(HttpStatus.CONFLICT, ex.getMessage(), null));
    }

    /**
     * Catch-all for any unexpected exception.
     * Returns 500 Internal Server Error with a generic message.
     *
     * WHY generic message? Leaking exception details (stack traces, SQL errors,
     * internal class names) is a security vulnerability (CWE-209).
     * We log the full exception server-side but return only a safe message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildErrorBody(HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Check server logs.", null));
    }

    // ── HELPER ────────────────────────────────────────────────────────────

    private Map<String, Object> buildErrorBody(HttpStatus status, String message, String detail) {
        Map<String, Object> body = new LinkedHashMap<>(); // Preserves insertion order in JSON
        body.put("timestamp", Instant.now().toString());
        body.put("status",    status.value());
        body.put("error",     message);
        if (detail != null) body.put("detail", detail);
        return body;
    }
}
