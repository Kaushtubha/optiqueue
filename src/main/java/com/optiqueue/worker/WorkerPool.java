package com.optiqueue.worker;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    // ── DEPENDENCIES ──────────────────────────────────────────────────────

    private final JobBlockingQueue blockingQueue;
    private final JobRepository    jobRepository;
    private final OptiQueueProperties props;
    private final com.optiqueue.worker.handler.JobHandlerRegistry handlerRegistry;

    // ── STATE ─────────────────────────────────────────────────────────────

    /** The thread pool. Fixed size = predictable resource usage. */
    private final ExecutorService executor;

    /**
     * Cooperative shutdown flag.
     * AtomicBoolean: thread-safe read/write without synchronization.
     * Worker threads check this flag in their loop condition.
     * When true, threads finish their current job and exit cleanly.
     * WHY not volatile boolean? AtomicBoolean's compareAndSet() gives
     * atomic read-modify-write. Here we only need reads + one write,
     * so volatile would work too — AtomicBoolean is idiomatic Java.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Unique identifier for this worker node (stamped on jobs this node claims). */
    private final String workerId;

    // ── CONSTRUCTOR ───────────────────────────────────────────────────────

    public WorkerPool(JobBlockingQueue blockingQueue,
                      JobRepository jobRepository,
                      OptiQueueProperties props,
                      com.optiqueue.worker.handler.JobHandlerRegistry handlerRegistry) {

        this.blockingQueue   = blockingQueue;
        this.jobRepository   = jobRepository;
        this.props           = props;
        this.handlerRegistry = handlerRegistry;

        // Read worker ID from env var (injected by Docker Compose).
        // Falls back to a random UUID for local dev runs outside Docker.
        this.workerId = System.getenv().getOrDefault("WORKER_ID",
                            "worker-" + UUID.randomUUID().toString().substring(0, 8));

        int poolSize = props.getWorker().getPoolSize();
        log.info("Initializing WorkerPool: workerId={}, poolSize={}", workerId, poolSize);

        // ── CREATE THREAD POOL ─────────────────────────────────────────────
        // Custom ThreadFactory: names threads + sets daemon=true.
        //
        // daemon=true: JVM exits when only daemon threads remain.
        // Without this, if the main application shuts down but workers
        // are blocked in take(), the JVM hangs indefinitely waiting for
        // non-daemon threads to finish.
        //
        // The @PreDestroy shutdown() method signals workers to stop BEFORE
        // the JVM would rely on daemon behaviour — daemon is a safety net.
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory namedFactory = runnable -> {
            Thread t = new Thread(runnable,
                "optiqueue-worker-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        this.executor = Executors.newFixedThreadPool(poolSize, namedFactory);

        // Start all worker threads immediately at construction.
        // They block in take() until jobs arrive.
        for (int i = 0; i < poolSize; i++) {
            executor.submit(this::workerLoop);
        }

        log.info("WorkerPool started: {} threads listening for jobs", poolSize);
    }

    // ── WORKER LOOP ───────────────────────────────────────────────────────

    /**
     * The main loop run by each worker thread.
     *
     * Pattern: infinite loop, take job, execute, repeat.
     * Exits when: running=false AND take() returns null (shutdown signal).
     *
     * INTERVIEW Q: "How does this implement the consumer in producer-consumer?"
     * A: blockingQueue.take() blocks if the queue is empty — the worker
     *    thread is suspended (not spinning), using zero CPU. When the
     *    scheduler (producer) adds a job and signals notEmpty, exactly
     *    one worker wakes up and claims the job.
     */
    private void workerLoop() {
        String threadName = Thread.currentThread().getName();
        log.info("Worker thread started: {}", threadName);

        while (running.get()) {
            Job job = null;
            try {
                // Block until a job is available OR shutdown is signalled.
                // Returns null on shutdown (JobBlockingQueue.shutdown flag set).
                job = blockingQueue.poll(2, TimeUnit.SECONDS);

                if (job == null) {
                    // Either shutdown or 2s timeout with empty queue.
                    // Loop back and re-check running flag.
                    continue;
                }

                executeJob(job);

            } catch (InterruptedException e) {
                // Thread was interrupted — this is the shutdown signal.
                Thread.currentThread().interrupt(); // Restore interrupt flag
                log.info("Worker thread interrupted (shutdown): {}", threadName);
                break;

            } catch (Exception e) {
                // Unexpected exception in executeJob — log but don't crash the thread.
                // A crashing worker thread shrinks the pool permanently, so we
                // catch broadly here and let the retry logic handle the job state.
                log.error("Unexpected exception in worker {}: job={}", threadName,
                    job != null ? job.getId() : "null", e);
            }
        }

        log.info("Worker thread exiting: {}", threadName);
    }

    // ── JOB EXECUTION ─────────────────────────────────────────────────────

    /**
     * Executes a single job end-to-end.
     *
     * Steps:
     *   1. Claim the job (PENDING → IN_PROGRESS, stamp workerId)
     *   2. Simulate work (real system would dispatch to a handler by jobType)
     *   3. Mark COMPLETED with result
     *   4. On any exception: mark FAILED (retry engine handles retry in Step 7)
     *
     * ALL DB writes are @Transactional at the repository layer.
     * WHY is this method not @Transactional itself?
     *   The job execution (step 2) can take seconds or minutes. Holding a
     *   DB transaction open for that duration would exhaust the connection pool.
     *   Instead: short transaction for CLAIM (step 1), no transaction during
     *   execution (step 2), short transaction for COMPLETE/FAIL (step 3/4).
     *   This is the "saga" pattern for long-running operations.
     *
     * PRODUCTION NOTE: In a real system, step 2 would call a JobHandler
     * interface: handler = handlerRegistry.get(job.getJobType()); handler.execute(job);
     * Different job types would have different implementations.
     * Here we simulate with Thread.sleep() for demonstration.
     */
    private void executeJob(Job job) {
        org.slf4j.MDC.put("jobId", job.getId() != null ? job.getId().toString() : "unknown");
        org.slf4j.MDC.put("workerId", workerId);
        try {
            log.info("[{}] Claiming job: id={}, type={}, priority={}",
                workerId, job.getId(), job.getJobType(), job.getPriority());

            // ── STEP 1: CLAIM ──────────────────────────────────────────────────
            // Load fresh from DB (may have been updated since scheduler loaded it)
            Job fresh = jobRepository.findById(job.getId()).orElse(null);
            if (fresh == null) {
                log.warn("Job {} not found in DB — skipping (may have been cancelled)", job.getId());
                return;
            }

            // Check it's still PENDING (idempotency: another worker might have grabbed it)
            if (fresh.getStatus() != JobStatus.PENDING && fresh.getStatus() != JobStatus.RETRYING) {
                log.warn("Job {} is already {} — skipping (concurrent claim detected)",
                    fresh.getId(), fresh.getStatus());
                return;
            }

            try {
                fresh.claimBy(workerId); // PENDING → IN_PROGRESS
                jobRepository.save(fresh);
            } catch (Exception e) {
                // Claim failed (e.g., DB down) — put job back in queue for retry
                log.error("Failed to claim job {}: {}", fresh.getId(), e.getMessage());
                try {
                    blockingQueue.put(fresh); // Back to queue
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return;
            }

            // ── STEP 2: EXECUTE ────────────────────────────────────────────────
            String result;
            try {
                com.optiqueue.worker.handler.JobHandler handler = handlerRegistry.getHandler(fresh.getJobType());
                result = handler.execute(fresh);
            } catch (Exception e) {
                // Job threw an exception — mark as FAILED, let retry engine handle
                log.warn("[{}] Job {} failed: {}", workerId, fresh.getId(), e.getMessage());

                fresh.recordFailure(
                    e.getMessage(),
                    props.getRetry().getInitialDelayMs(),
                    true
                );
                jobRepository.save(fresh);

                log.info("[{}] Job {} → status={}, retryCount={}/{}",
                    workerId, fresh.getId(), fresh.getStatus(),
                    fresh.getRetryCount(), fresh.getMaxRetries());
                return;
            }

            // ── STEP 3: COMPLETE ───────────────────────────────────────────────
            fresh.complete(result);
            jobRepository.save(fresh);

            log.info("[{}] Job {} → COMPLETED in {}ms",
                workerId, fresh.getId(),
                fresh.getCompletedAt().toEpochMilli() - fresh.getStartedAt().toEpochMilli());
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    // ── PUBLIC API ────────────────────────────────────────────────────────

    /**
     * Submits a job to the worker queue.
     * Called by SchedulerService (the producer).
     *
     * Uses offer() with a 500ms timeout rather than put():
     * If the queue is full, we skip this batch and try again on the next
     * scheduler tick rather than blocking the scheduler thread indefinitely.
     *
     * INTERVIEW Q: "What happens to jobs that don't fit in the blocking queue?"
     * A: They stay in the DB as PENDING. The scheduler polls the DB on every
     *    tick, so they'll be picked up in the next cycle when workers drain
     *    some space. The blocking queue is a buffer between DB and workers,
     *    not the authoritative job store — that's always the DB.
     */
    public boolean submit(Job job) {
        try {
            boolean accepted = blockingQueue.offer(job, 500, TimeUnit.MILLISECONDS);
            if (!accepted) {
                log.warn("Blocking queue full — job {} will be retried next tick", job.getId());
            }
            return accepted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Graceful shutdown — called by Spring before the application context closes.
     *
     * Sequence:
     *   1. Set running=false — workers finish current job then exit their loop
     *   2. Signal the blocking queue — unblocks any workers stuck in take()
     *   3. executor.shutdown() — no new tasks accepted
     *   4. awaitTermination(30s) — wait for in-flight jobs to complete
     *   5. executor.shutdownNow() — interrupt any stragglers (force stop)
     *
     * Jobs in-flight at shutdown will be INCOMPLETE in DB (status=IN_PROGRESS).
     * On next startup, the failover mechanism (Step 10) detects these and
     * resets them to PENDING. This is the recovery guarantee.
     *
     * PRODUCTION NOTE: A 30s shutdown timeout is common in Kubernetes.
     * Kubernetes sends SIGTERM, waits `terminationGracePeriodSeconds` (default 30s),
     * then sends SIGKILL. Our 30s timeout aligns with this.
     */
    @PreDestroy
    public void shutdown() {
        log.info("WorkerPool shutting down...");

        running.set(false);                    // Signal workers to stop looping
        blockingQueue.signalAllForShutdown();  // Unblock any threads in take()
        executor.shutdown();                   // No more tasks accepted

        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("WorkerPool did not terminate in 30s — forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("WorkerPool shut down complete");
    }

    // ── MONITORING ────────────────────────────────────────────────────────

    public String getWorkerId() { return workerId; }

    public int getQueueSize() { return blockingQueue.size(); }

    public int getQueueCapacity() { return blockingQueue.capacity(); }

    public boolean isRunning() { return running.get(); }
}
