package com.optiqueue.domain;

/**
 * JobStatus — the complete lifecycle state machine for a job.
 *
 * States:
 *   PENDING      → Job submitted, waiting in the priority queue to be picked up.
 *   IN_PROGRESS  → A worker thread has claimed this job and is executing it.
 *   COMPLETED    → Job finished successfully. Result is stored in jobs.result.
 *   FAILED       → Job threw an exception. Will be retried if retry_count < max_retries.
 *   RETRYING     → Job failed but has retries remaining. Waiting for next_retry_at.
 *   DEAD_LETTER  → Job exhausted all retries. Moved to DLQ for manual inspection.
 *   CANCELLED    → Job was cancelled via API before or during processing.
 *
 * INTERVIEW Q: "What's a dead-letter queue and why do you need one?"
 * A: A DLQ is where jobs land after exhausting all retry attempts. Without it,
 *    failed jobs either disappear silently (data loss) or retry forever (infinite loop).
 *    The DLQ gives ops teams a place to inspect failures, fix the root cause,
 *    and manually requeue jobs. Every production message queue system
 *    (SQS, RabbitMQ, Kafka) has a DLQ concept for exactly this reason.
 */
public enum JobStatus {

    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    RETRYING,
    DEAD_LETTER,
    CANCELLED;

    /**
     * Validates whether transitioning from `current` to `next` is legal.
     *
     * This enforces the state machine contract at the application layer.
     * The DB has no knowledge of this logic — it would happily store
     * COMPLETED → PENDING if we let it. This method prevents that.
     *
     * Called before every status update in JobService.
     *
     * @param current the job's current status
     * @param next    the desired next status
     * @return true if the transition is legal
     */
    public static boolean isValidTransition(JobStatus current, JobStatus next) {
        return switch (current) {
            // From PENDING: can be picked up, or cancelled
            case PENDING      -> next == IN_PROGRESS || next == CANCELLED;

            // From IN_PROGRESS: can succeed, fail, or be cancelled mid-flight
            case IN_PROGRESS  -> next == COMPLETED || next == FAILED || next == CANCELLED;

            // From FAILED: retry engine decides — retry or move to DLQ
            case FAILED       -> next == RETRYING || next == DEAD_LETTER;

            // From RETRYING: job re-enters the queue as PENDING for next attempt
            case RETRYING     -> next == PENDING;

            // Terminal states — no transitions out
            case COMPLETED, DEAD_LETTER, CANCELLED -> false;
        };
    }
}
