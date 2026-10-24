package com.optiqueue.retry;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryEngine {

    private static final Logger log = LoggerFactory.getLogger(RetryEngine.class);

    private final JobRepository       jobRepository;
    private final OptiQueueProperties props;

    public RetryEngine(JobRepository jobRepository, OptiQueueProperties props) {
        this.jobRepository = jobRepository;
        this.props = props;
    }

    // ── RETRY SCAN ────────────────────────────────────────────────────────

    /**
     * Main retry loop — runs every 5 seconds after the last execution completes.
     *
     * Finds all FAILED jobs where:
     *   - retryCount < maxRetries (still has attempts left)
     *   - nextRetryAt <= now (backoff delay has elapsed)
     *
     * Transitions each matching job: FAILED → RETRYING → PENDING
     *
     * WHY two transitions (FAILED → RETRYING → PENDING) instead of one?
     *   RETRYING is a observable intermediate state. External monitoring
     *   (dashboards, alerts) can distinguish:
     *     - FAILED: job errored, backoff in progress
     *     - RETRYING: job is being moved back to queue RIGHT NOW
     *   It makes the system's state machine visible to operators.
     *   In practice, RETRYING lasts milliseconds — within this same transaction.
     *
     * @Transactional: both status changes committed atomically.
     *   If the app crashes between FAILED→RETRYING and RETRYING→PENDING,
     *   the job stays FAILED (transaction rolled back) — safe, will retry next tick.
     */
    @Scheduled(fixedDelayString = "${retry.scan-interval-ms:5000}", initialDelay = 10000)
    @Transactional
    public void processRetries() {
        Instant now = Instant.now();
        List<Job> readyJobs = jobRepository.findJobsReadyForRetry(now);

        if (readyJobs.isEmpty()) {
            log.debug("RetryEngine: no jobs ready for retry at {}", now);
            return;
        }

        log.info("RetryEngine: found {} jobs ready for retry", readyJobs.size());

        for (Job job : readyJobs) {
            retryJob(job);
        }
    }

    private void retryJob(Job job) {
        try {
            log.info("Retrying job {}: attempt {}/{}, was scheduled for {}",
                job.getId(), job.getRetryCount() + 1, job.getMaxRetries(), job.getNextRetryAt());

            // FAILED → RETRYING (visible intermediate state)
            job.setStatus(JobStatus.RETRYING);

            // RETRYING → PENDING (re-enter the queue)
            job.setStatus(JobStatus.PENDING);
            job.setNextRetryAt(null);        // Clear the retry timer
            job.setAssignedWorkerId(null);   // No longer assigned to old worker

            jobRepository.save(job);

            log.info("Job {} reset to PENDING for retry attempt {}/{}",
                job.getId(), job.getRetryCount() + 1, job.getMaxRetries());

        } catch (Exception e) {
            log.error("Failed to reset job {} for retry: {}", job.getId(), e.getMessage());
            // Don't re-throw — continue processing other jobs in the batch
        }
    }

    // ── DEAD-LETTER SCAN ──────────────────────────────────────────────────

    /**
     * Scans for FAILED jobs that should be moved to DEAD_LETTER.
     *
     * In normal flow, Job.recordFailure() moves jobs to DEAD_LETTER directly
     * when retryCount >= maxRetries. This scan is a safety net that catches
     * any jobs that somehow ended up FAILED with no more retries left
     * (e.g., maxRetries was reduced via config after the job was submitted).
     *
     * Runs every 30 seconds — less frequently since it's a cleanup/safety task.
     */
    @Scheduled(fixedDelayString = "${retry.dlq-scan-interval-ms:30000}", initialDelay = 15000)
    @Transactional
    public void processDeadLetters() {
        // Query FAILED jobs that have exhausted retries but were not yet moved to DLQ
        List<Job> exhaustedJobs = jobRepository.findExhaustedFailedJobs();

        if (exhaustedJobs.isEmpty()) return;

        log.warn("DLQ scan: found {} jobs that exhausted retries without DLQ transition",
            exhaustedJobs.size());

        for (Job job : exhaustedJobs) {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.setDeadLetter(true);
            jobRepository.save(job);
            log.warn("Job {} moved to DEAD_LETTER (retryCount={}, maxRetries={})",
                job.getId(), job.getRetryCount(), job.getMaxRetries());
        }
    }

    // ── BACKOFF CALCULATOR (standalone utility) ───────────────────────────

    /**
     * Calculates the next retry delay using exponential backoff with optional jitter.
     *
     * Called externally by WorkerPool (Step 5) via Job.recordFailure().
     * Extracted here as a testable static utility.
     *
     * STRATEGIES:
     *
     * 1. Pure exponential (no jitter):
     *    delay = initialDelay × 2^(attempt - 1)
     *    attempt=1: 1s, attempt=2: 2s, attempt=3: 4s, attempt=4: 8s, attempt=5: 16s
     *    Problem: synchronized retries if many jobs fail at the same time.
     *
     * 2. Full jitter (AWS recommended for distributed systems):
     *    delay = random(0, initialDelay × 2^(attempt - 1))
     *    Spreads retries uniformly over the backoff window.
     *    Best for avoiding thundering herd at downstream services.
     *
     * 3. Equal jitter (balanced approach):
     *    half = initialDelay × 2^(attempt - 1) / 2
     *    delay = half + random(0, half)
     *    Ensures minimum delay (not zero like full jitter) while adding spread.
     *
     * INTERVIEW Q: "Why does jitter help thundering herd?"
     * A: Without jitter, N jobs that all failed at time T all retry at time T+delay.
     *    They hit the downstream service simultaneously — creating a new spike
     *    that may cause more failures, triggering another synchronized retry wave.
     *    Jitter randomizes each job's retry time within the window, spreading
     *    N retries uniformly → downstream sees steady load, not spikes.
     *
     * @param attempt      retry attempt number (1-based)
     * @param initialDelay base delay in milliseconds
     * @param useJitter    true = full jitter, false = pure exponential
     * @return delay in milliseconds before next retry attempt
     */
    public static long calculateBackoffMs(int attempt, long initialDelay, boolean useJitter) {
        // Cap at attempt=20 to prevent overflow: 2^20 = ~1M
        int cappedAttempt = Math.min(attempt, 20);

        // Base exponential delay: initialDelay × 2^(attempt-1)
        long exponentialMs = initialDelay * (1L << (cappedAttempt - 1));

        // Cap at 1 hour max delay (prevents jobs from being stuck for days)
        long maxDelayMs = 60 * 60 * 1000L; // 1 hour in ms
        exponentialMs = Math.min(exponentialMs, maxDelayMs);

        if (useJitter) {
            // Full jitter: random in [0, exponentialMs]
            // ThreadLocalRandom: faster than Random in multi-threaded contexts
            // (avoids contention on a shared seed)
            return ThreadLocalRandom.current().nextLong(0, exponentialMs + 1);
        }

        return exponentialMs;
    }

    /**
     * Calculates delay with equal jitter (balanced — ensures minimum wait).
     *
     * @param attempt      retry attempt number (1-based)
     * @param initialDelay base delay in milliseconds
     * @return delay in ms with equal jitter applied
     */
    public static long calculateEqualJitterBackoffMs(int attempt, long initialDelay) {
        int cappedAttempt  = Math.min(attempt, 20);
        long exponentialMs = initialDelay * (1L << (cappedAttempt - 1));
        long maxDelayMs    = 60 * 60 * 1000L;
        exponentialMs      = Math.min(exponentialMs, maxDelayMs);

        long half = exponentialMs / 2;
        return half + ThreadLocalRandom.current().nextLong(0, half + 1);
    }
}
