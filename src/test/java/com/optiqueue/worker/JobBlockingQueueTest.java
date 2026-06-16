package com.optiqueue.worker;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * JobBlockingQueueTest — tests for the custom thread-safe blocking queue.
 *
 * TWO CATEGORIES OF TESTS:
 *
 * 1. Single-threaded correctness tests (no concurrency):
 *    Verify the queue behaves like a FIFO bounded queue in isolation.
 *
 * 2. Multi-threaded stress tests:
 *    Simulate actual producer-consumer workloads and verify:
 *      - No jobs are lost
 *      - No jobs are duplicated
 *      - No deadlock occurs (@Timeout annotation kills the test if it hangs)
 *      - Blocking behaviour works (producer blocks when full, consumer blocks when empty)
 *
 * INTERVIEW Q: "How do you test for race conditions?"
 * A: You can't deterministically reproduce every race with unit tests alone.
 *    Instead:
 *      a) Stress test with many threads and high iteration counts — increases
 *         the probability of hitting races
 *      b) Use tools: ThreadSanitizer (C++), Java PathFinder (model checking),
 *         or Lincheck (property-based concurrency testing for JVM)
 *      c) Use @Timeout to catch deadlocks — a deadlock manifests as a test
 *         that never completes
 *      d) Use AtomicInteger for shared counters — guarantees visibility
 *         across threads without adding extra synchronization
 */
@DisplayName("JobBlockingQueue — Thread-Safe Blocking Queue Tests")
class JobBlockingQueueTest {

    private static final int CAPACITY = 5;
    private JobBlockingQueue queue;

    @BeforeEach
    void setUp() {
        queue = new JobBlockingQueue(CAPACITY);
    }

    // ── SINGLE-THREADED CORRECTNESS ───────────────────────────────────────

    @Test
    @DisplayName("Empty queue: size=0, isEmpty=true, poll with timeout returns null")
    void emptyQueueBehaviour() throws InterruptedException {
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
        assertThat(queue.capacity()).isEqualTo(CAPACITY);

        // poll with 0 timeout should return null immediately (no blocking)
        Job result = queue.poll(0, TimeUnit.MILLISECONDS);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Put and take: single producer-consumer, FIFO order")
    void singlePutTake() throws InterruptedException {
        Job job1 = buildJob("job-1");
        Job job2 = buildJob("job-2");
        Job job3 = buildJob("job-3");

        queue.put(job1);
        queue.put(job2);
        queue.put(job3);

        assertThat(queue.size()).isEqualTo(3);

        // FIFO: jobs come out in insertion order
        assertThat(queue.take()).isSameAs(job1);
        assertThat(queue.take()).isSameAs(job2);
        assertThat(queue.take()).isSameAs(job3);

        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Offer with timeout: returns false when queue is full and timeout expires")
    void offerTimesOutWhenFull() throws InterruptedException {
        // Fill to capacity
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(buildJob("job-" + i));
        }
        assertThat(queue.size()).isEqualTo(CAPACITY);

        // Offering one more should fail after 100ms
        long start = System.currentTimeMillis();
        boolean offered = queue.offer(buildJob("overflow"), 100, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(offered).isFalse();
        assertThat(elapsed).isGreaterThanOrEqualTo(90); // Allow 10ms jitter
        assertThat(queue.size()).isEqualTo(CAPACITY);   // Size unchanged
    }

    @Test
    @DisplayName("signalAllForShutdown: unblocks a waiting consumer without adding a job")
    @Timeout(3) // Fails if thread doesn't unblock within 3 seconds → catches deadlock
    void shutdownSignalUnblocksConsumer() throws InterruptedException {
        // Consumer thread blocks because queue is empty
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger(0);

        Thread consumer = new Thread(() -> {
            try {
                consumerBlocked.countDown();  // Signal that consumer is about to block
                queue.poll(5, TimeUnit.SECONDS); // Block waiting for a job
            } catch (InterruptedException e) {
                interruptCount.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        consumerBlocked.await(); // Wait until consumer is about to block
        Thread.sleep(50);        // Give consumer time to actually enter await()

        queue.signalAllForShutdown(); // Should wake the consumer

        consumer.join(2000); // Consumer should finish within 2s
        assertThat(consumer.isAlive()).isFalse(); // Consumer exited cleanly
    }

    // ── MULTI-THREADED STRESS TESTS ───────────────────────────────────────

    @Test
    @DisplayName("Stress: N producers + N consumers, all jobs delivered exactly once")
    @Timeout(30) // Deadlock detection: if it runs >30s, something is blocked forever
    void stressTestNoLostJobs() throws InterruptedException {
        final int NUM_PRODUCERS  = 4;
        final int NUM_CONSUMERS  = 4;
        final int JOBS_PER_PRODUCER = 500;
        final int TOTAL_JOBS = NUM_PRODUCERS * JOBS_PER_PRODUCER;

        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);

        ExecutorService producerPool = Executors.newFixedThreadPool(NUM_PRODUCERS);
        ExecutorService consumerPool = Executors.newFixedThreadPool(NUM_CONSUMERS);

        CountDownLatch allConsumed = new CountDownLatch(TOTAL_JOBS);

        // Start consumers first (they block on empty queue)
        for (int c = 0; c < NUM_CONSUMERS; c++) {
            consumerPool.submit(() -> {
                while (true) {
                    try {
                        // poll with timeout: returns null when queue is empty AND
                        // either timeout expired OR queue is shut down
                        Job job = queue.poll(5, TimeUnit.SECONDS);
                        if (job == null) break; // Shutdown or timeout — exit
                        consumed.incrementAndGet();
                        allConsumed.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        // Start producers
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            producerPool.submit(() -> {
                for (int i = 0; i < JOBS_PER_PRODUCER; i++) {
                    try {
                        queue.put(buildJob(UUID.randomUUID().toString()));
                        produced.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        // Wait for all jobs to be consumed (with 25s timeout)
        boolean allDone = allConsumed.await(25, TimeUnit.SECONDS);

        producerPool.shutdown();
        consumerPool.shutdown();

        assertThat(allDone)
            .as("All jobs should be consumed within timeout — if false, there's a deadlock or starvation")
            .isTrue();

        assertThat(produced.get()).isEqualTo(TOTAL_JOBS);
        assertThat(consumed.get()).isEqualTo(TOTAL_JOBS);
    }

    @Test
    @DisplayName("Blocking: producer blocks when full, resumes when consumer drains")
    @Timeout(5)
    void producerBlocksWhenFull() throws InterruptedException {
        // Fill the queue to capacity
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(buildJob("fill-" + i));
        }

        AtomicInteger putSucceeded = new AtomicInteger(0);
        CountDownLatch producerStarted = new CountDownLatch(1);

        // This producer should block (queue is full)
        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                queue.put(buildJob("blocked-job")); // Should block here
                putSucceeded.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        producerStarted.await();
        Thread.sleep(100); // Give producer time to actually block in notFull.await()

        // Producer should still be alive (blocked)
        assertThat(producer.isAlive()).isTrue();
        assertThat(putSucceeded.get()).isZero();

        // Consumer drains one job → producer should unblock
        queue.take();

        producer.join(2000); // Producer should finish within 2s
        assertThat(putSucceeded.get()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(CAPACITY); // Drained 1, added 1 = back to full
    }

    @Test
    @DisplayName("Blocking: consumer blocks when empty, resumes when producer adds job")
    @Timeout(5)
    void consumerBlocksWhenEmpty() throws InterruptedException {
        List<Job> received = new CopyOnWriteArrayList<>();
        CountDownLatch consumerReady = new CountDownLatch(1);

        // Consumer blocks on empty queue
        Thread consumer = new Thread(() -> {
            try {
                consumerReady.countDown();
                Job job = queue.take(); // Blocks here
                received.add(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        consumerReady.await();
        Thread.sleep(100); // Let consumer reach take() → await()

        assertThat(consumer.isAlive()).isTrue();
        assertThat(received).isEmpty();

        // Producer adds a job → consumer wakes up
        Job expected = buildJob("wake-up-job");
        queue.put(expected);

        consumer.join(2000);
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isSameAs(expected);
    }

    // ── HELPER ────────────────────────────────────────────────────────────

    private Job buildJob(String type) {
        return Job.builder()
            .id(UUID.randomUUID())
            .jobType(type)
            .priority(5)
            .status(JobStatus.PENDING)
            .retryCount(0)
            .maxRetries(5)
            .isDeadLetter(false)
            .build();
    }
}
