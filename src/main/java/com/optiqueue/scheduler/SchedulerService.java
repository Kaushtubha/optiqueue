package com.optiqueue.scheduler;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.redis.DistributedLockService;
import com.optiqueue.repository.JobRepository;
import com.optiqueue.worker.WorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates periodic polling of pending jobs from PostgreSQL and dispatches them into the worker pool.
 * Uses distributed locking in Redis to ensure single-leader scheduling across multi-node clusters.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final JobRepository         jobRepository;
    private final WorkerPool            workerPool;
    private final OptiQueueProperties   props;
    private final DistributedLockService lockService;

    /**
     * Constructs a new SchedulerService with required repositories, worker pools, and distributed coordination.
     *
     * @param jobRepository the persistent job storage repository
     * @param workerPool the execution pool running dispatched background jobs
     * @param props application configuration properties
     * @param lockService distributed lock coordinator backed by Redis
     */
    public SchedulerService(JobRepository jobRepository, WorkerPool workerPool, OptiQueueProperties props, DistributedLockService lockService) {
        this.jobRepository = jobRepository;
        this.workerPool = workerPool;
        this.props = props;
        this.lockService = lockService;
    }

    /**
     * In-memory priority queue for sorting this poll's batch of jobs.
     * Rebuilt on every scheduler tick from fresh DB data.
     *
     * WHY rebuild every tick vs maintaining a persistent in-memory queue?
     *   Persistent queue risks diverging from DB truth (e.g., jobs cancelled
     *   via API while sitting in memory). Rebuilding from DB every tick ensures
     *   the scheduler always works from the authoritative source.
     *   Cost: one SELECT per tick (covered by index) — acceptable.
     */
    private final JobPriorityQueue priorityQueue = new JobPriorityQueue(256);

    /**
     * Lock protecting the priority queue during the
     * "load from DB → sort → drain to blocking queue" sequence.
     * Ensures no partial state is visible if shutdown() interrupts mid-cycle.
     */
    private final ReentrantLock schedulerLock = new ReentrantLock();

    // ── SCHEDULER TICK ────────────────────────────────────────────────────

    /**
     * Main scheduling loop — runs every 2 seconds after the last execution ends.
     *
     * What happens each tick:
     *   1. Query DB: top N PENDING jobs (N = worker pool size * 2, to keep workers fed)
     *   2. Insert all into the priority queue (sorts by priority+EDF+FIFO)
     *   3. Drain queue into the blocking queue (workers pick up from there)
     *   4. Rebuild priority queue for next tick (clear it)
     *
     * @Transactional(readOnly=true): wraps the DB read in a transaction.
     *   readOnly=true: tells Hibernate to skip dirty-check on flush (performance).
     *   Also lets Postgres optimize: read-only transactions can use hot standby replicas.
     *
     * initialDelay=5000: Wait 5s after startup before first tick.
     *   Gives Flyway time to run migrations and Spring context to fully initialize
     *   before we start hammering the DB.
     */
    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:2000}",
               initialDelay    = 5000)
    @Transactional(readOnly = true)
    public void schedulerTick() {
        if (!workerPool.isRunning()) return; // Skip if shutting down

        // Acquire distributed lock — only ONE node runs the scheduler per tick.
        // TTL = 8s (just under the 10s scheduler interval with some buffer).
        // If this node is slow and the lock expires, another node can take over.
        String holderId = workerPool.getWorkerId();
        boolean lockAcquired = lockService.tryAcquireSchedulerLock(holderId, 8_000);
        if (!lockAcquired) {
            log.debug("Scheduler tick skipped — lock held by another node");
            return;
        }

        schedulerLock.lock();
        try {
            runSchedulerCycle();
        } finally {
            schedulerLock.unlock();
            lockService.releaseSchedulerLock(holderId);
        }
    }

    private void runSchedulerCycle() {
        // How many jobs to fetch: 2x pool size to keep workers busy.
        // If pool has 4 workers and blocking queue has capacity 100,
        // we load up to 8 jobs per tick — enough to keep workers fed
        // without loading the entire backlog into memory.
        int fetchLimit = props.getWorker().getPoolSize() * 2;

        List<Job> pendingJobs = jobRepository
            .findTopNByStatusOrderByPriorityAndDeadline(JobStatus.PENDING, fetchLimit);

        if (pendingJobs.isEmpty()) {
            log.debug("Scheduler tick: no PENDING jobs found");
            return;
        }

        log.debug("Scheduler tick: found {} PENDING jobs", pendingJobs.size());

        // ── STAGE 1: SORT via priority queue ────────────────────────────────
        // Insert all fetched jobs into the heap — O(k log k) where k = batch size
        for (Job job : pendingJobs) {
            priorityQueue.insert(job);
        }

        // ── STAGE 2: DRAIN into blocking queue ──────────────────────────────
        // Poll from priority queue (highest priority first) and submit to workers.
        // submit() uses offer() with 500ms timeout — if blocking queue is full,
        // we leave remaining jobs in DB for next tick.
        int submitted = 0;
        while (!priorityQueue.isEmpty()) {
            Job job = priorityQueue.poll();
            boolean accepted = workerPool.submit(job);
            if (accepted) {
                submitted++;
            } else {
                // Blocking queue is full — don't submit more this tick
                log.debug("Blocking queue full at {}/{} — {} jobs deferred to next tick",
                    workerPool.getQueueSize(), workerPool.getQueueCapacity(),
                    priorityQueue.size() + 1);
                // Drain remaining from priority queue without submitting
                while (!priorityQueue.isEmpty()) priorityQueue.poll();
                break;
            }
        }

        log.debug("Scheduler tick complete: submitted={}, queueSize={}",
            submitted, workerPool.getQueueSize());
    }
}
