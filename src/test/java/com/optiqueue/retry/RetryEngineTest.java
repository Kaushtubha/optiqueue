package com.optiqueue.retry;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * RetryEngineTest — unit tests for retry logic and backoff calculation.
 *
 * These are pure unit tests — no Spring context, no DB, no scheduler.
 * Tests cover:
 *   1. Backoff calculation correctness (pure math, deterministic)
 *   2. Jitter bounds (statistical property — verify range, not exact value)
 *   3. Job.recordFailure() state transitions (integration of Steps 2 + 7)
 *   4. Overflow protection for large attempt numbers
 *
 * INTERVIEW Q: "How do you test randomised behaviour?"
 * A: You can't assert the exact random value, but you CAN assert:
 *    - The value is within the expected range [0, maxDelay]
 *    - Over many samples, the distribution is approximately uniform
 *    - Edge cases (attempt=0, attempt=MAX_INT) don't throw exceptions
 *    Property-based testing (e.g., jqwik library) is the gold standard:
 *    it generates random inputs and verifies invariants hold for all of them.
 */
@DisplayName("RetryEngine — Backoff and State Machine Tests")
class RetryEngineTest {

    private static final long INITIAL_DELAY_MS = 1000L;

    // ── PURE EXPONENTIAL BACKOFF ──────────────────────────────────────────

    @Test
    @DisplayName("Exponential: attempt 1 → 1s, 2 → 2s, 3 → 4s, 4 → 8s, 5 → 16s")
    void exponentialSequenceIsCorrect() {
        assertThat(RetryEngine.calculateBackoffMs(1, INITIAL_DELAY_MS, false)).isEqualTo(1000L);
        assertThat(RetryEngine.calculateBackoffMs(2, INITIAL_DELAY_MS, false)).isEqualTo(2000L);
        assertThat(RetryEngine.calculateBackoffMs(3, INITIAL_DELAY_MS, false)).isEqualTo(4000L);
        assertThat(RetryEngine.calculateBackoffMs(4, INITIAL_DELAY_MS, false)).isEqualTo(8000L);
        assertThat(RetryEngine.calculateBackoffMs(5, INITIAL_DELAY_MS, false)).isEqualTo(16000L);
    }

    @Test
    @DisplayName("Exponential: capped at 1 hour maximum regardless of attempt number")
    void exponentialCapsAtOneHour() {
        long oneHourMs = 60 * 60 * 1000L;
        // Attempt 50 would be astronomically large without the cap
        long delay = RetryEngine.calculateBackoffMs(50, INITIAL_DELAY_MS, false);
        assertThat(delay).isEqualTo(oneHourMs);
    }

    @Test
    @DisplayName("Exponential: attempt numbers > 20 don't cause integer overflow")
    void noOverflowForLargeAttempts() {
        // Should not throw ArithmeticException or return negative value
        assertThatCode(() -> {
            long delay = RetryEngine.calculateBackoffMs(100, INITIAL_DELAY_MS, false);
            assertThat(delay).isPositive().isLessThanOrEqualTo(60 * 60 * 1000L);
        }).doesNotThrowAnyException();
    }

    // ── FULL JITTER BACKOFF ───────────────────────────────────────────────

    @Test
    @DisplayName("Full jitter: result is always in range [0, exponentialDelay]")
    void fullJitterIsWithinRange() {
        long maxForAttempt3 = 4000L; // 1000 * 2^(3-1) = 4000ms

        // Run 1000 samples — verify every single one is in bounds
        for (int i = 0; i < 1000; i++) {
            long delay = RetryEngine.calculateBackoffMs(3, INITIAL_DELAY_MS, true);
            assertThat(delay)
                .as("Full jitter delay must be in [0, %d]", maxForAttempt3)
                .isGreaterThanOrEqualTo(0L)
                .isLessThanOrEqualTo(maxForAttempt3);
        }
    }

    @Test
    @DisplayName("Full jitter: over many samples, produces values across the full range")
    void fullJitterProducesDistributedValues() {
        // Statistical test: over 10,000 samples, at least some should be
        // in the lower quarter and some in the upper quarter of the range.
        // (Verifies it's not always 0 or always max)
        long maxForAttempt3 = 4000L;
        long lowerThreshold  = maxForAttempt3 / 4;       // 1000ms
        long upperThreshold  = maxForAttempt3 * 3 / 4;   // 3000ms

        boolean sawLow  = false;
        boolean sawHigh = false;

        for (int i = 0; i < 10_000; i++) {
            long delay = RetryEngine.calculateBackoffMs(3, INITIAL_DELAY_MS, true);
            if (delay < lowerThreshold) sawLow = true;
            if (delay > upperThreshold) sawHigh = true;
            if (sawLow && sawHigh) break;
        }

        assertThat(sawLow)
            .as("Should produce values in lower quarter of range")
            .isTrue();
        assertThat(sawHigh)
            .as("Should produce values in upper quarter of range")
            .isTrue();
    }

    // ── EQUAL JITTER BACKOFF ──────────────────────────────────────────────

    @Test
    @DisplayName("Equal jitter: result is always in range [half, maxDelay]")
    void equalJitterIsWithinRange() {
        long maxForAttempt3 = 4000L; // 1000 * 2^(3-1) = 4000ms
        long half = maxForAttempt3 / 2; // 2000ms — minimum

        for (int i = 0; i < 1000; i++) {
            long delay = RetryEngine.calculateEqualJitterBackoffMs(3, INITIAL_DELAY_MS);
            assertThat(delay)
                .as("Equal jitter must be in [%d, %d]", half, maxForAttempt3)
                .isGreaterThanOrEqualTo(half)
                .isLessThanOrEqualTo(maxForAttempt3);
        }
    }

    // ── JOB STATE MACHINE INTEGRATION ─────────────────────────────────────

    @Test
    @DisplayName("recordFailure: first failure → FAILED with correct nextRetryAt")
    void firstFailureSetsFailed() {
        Job job = buildJob(0, 5);
        long initialDelay = 1000L;

        Instant before = Instant.now();
        job.recordFailure("Connection timeout", initialDelay);
        Instant after = Instant.now();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getRetryCount()).isEqualTo(1);
        assertThat(job.isDeadLetter()).isFalse();
        assertThat(job.getLastError()).isEqualTo("Connection timeout");

        // nextRetryAt should be ~1s from now (attempt 1: delay = 1000ms)
        // Allow ±200ms for test execution time
        assertThat(job.getNextRetryAt())
            .isAfter(before.plusMillis(800))
            .isBefore(after.plusMillis(1200));
    }

    @Test
    @DisplayName("recordFailure: failure on last attempt → DEAD_LETTER, isDeadLetter=true")
    void lastFailureMovesToDeadLetter() {
        // Already at 4 retries, maxRetries=5 → this failure is the 5th = DLQ
        Job job = buildJob(4, 5);

        job.recordFailure("Permanent failure", 1000L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(job.getRetryCount()).isEqualTo(5);
        assertThat(job.isDeadLetter()).isTrue();
    }

    @Test
    @DisplayName("recordFailure: backoff doubles correctly across attempts")
    void backoffDoublesEachAttempt() {
        long initialDelay = 1000L;

        // Simulate 4 consecutive failures and track the scheduled times
        long[] expectedDelays = {1000, 2000, 4000, 8000}; // 2^0, 2^1, 2^2, 2^3

        for (int attempt = 1; attempt <= 4; attempt++) {
            Job job = buildJob(attempt - 1, 5); // Pre-set retryCount to attempt-1
            Instant before = Instant.now();
            job.recordFailure("error", initialDelay);

            long actualDelay = job.getNextRetryAt().toEpochMilli() - before.toEpochMilli();
            long expected = expectedDelays[attempt - 1];

            // Allow ±100ms tolerance for test execution time
            assertThat(actualDelay)
                .as("Attempt %d should have delay ~%dms", attempt, expected)
                .isBetween(expected - 100, expected + 100);
        }
    }

    @Test
    @DisplayName("isReadyForRetry: true when FAILED + retries left + nextRetryAt passed")
    void isReadyForRetryWhenConditionsMet() {
        Job job = buildJob(1, 5);
        job.setStatus(JobStatus.FAILED);
        job.setNextRetryAt(Instant.now().minusSeconds(1)); // Already past due

        assertThat(job.isReadyForRetry()).isTrue();
    }

    @Test
    @DisplayName("isReadyForRetry: false when nextRetryAt is still in the future")
    void isNotReadyWhenBackoffPending() {
        Job job = buildJob(1, 5);
        job.setStatus(JobStatus.FAILED);
        job.setNextRetryAt(Instant.now().plusSeconds(60)); // Still waiting

        assertThat(job.isReadyForRetry()).isFalse();
    }

    // ── HELPER ────────────────────────────────────────────────────────────

    private Job buildJob(int retryCount, int maxRetries) {
        return Job.builder()
            .id(UUID.randomUUID())
            .jobType("TEST")
            .priority(5)
            .status(JobStatus.IN_PROGRESS)
            .retryCount(retryCount)
            .maxRetries(maxRetries)
            .isDeadLetter(false)
            .build();
    }
}
