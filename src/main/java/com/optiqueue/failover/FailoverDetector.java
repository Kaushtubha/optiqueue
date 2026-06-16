package com.optiqueue.failover;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.redis.HeartbeatService;
import com.optiqueue.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * FailoverDetector — detects dead workers and recovers their stuck jobs.
 *
 * Runs every 30 seconds, scanning for IN_PROGRESS jobs whose assigned worker
 * has not sent a heartbeat recently (heartbeat key expired from Redis).
 *
 * RECOVERY GUARANTEE:
 *   Combined with the retry engine (Step 7), this gives us:
 *   - At-least-once execution: every PENDING job will eventually run
 *   - Bounded recovery time: max time from crash to re-queue = heartbeatTtlMs
 *     (default: 15 seconds)
 *
 * DETECTION ALGORITHM:
 *   1. Find all IN_PROGRESS jobs where startedAt < now - heartbeatTtlMs
 *      (the job has been running longer than a worker can survive without heartbeat)
 *   2. For each such job, check Redis: EXISTS optiqueue:heartbeat:{assignedWorkerId}
 *   3. If key missing → worker dead → reset job to PENDING
 *   4. If key exists → worker alive but slow → leave it running
 *      (give slow jobs extra time; we don't want false positives killing long jobs)
 *
 * TRADEOFFS:
 *   - FALSE POSITIVE risk: a worker is alive but under GC pressure and temporarily
 *     missed its heartbeat window. Our 3× heartbeat interval TTL (15s for 5s interval)
 *     gives 2 missed heartbeats grace — greatly reduces false positives.
 *   - FALSE NEGATIVE risk: a worker died but its heartbeat key hasn't expired yet.
 *     Max exposure window = heartbeatTtlMs (15s). Acceptable.
 *
 * INTERVIEW Q: "What happens if the FailoverDetector itself crashes?"
 * A: The detector is stateless — it reads from DB and Redis, writes to DB.
 *    If it crashes, Spring's @Scheduled timer won't fire. Jobs stay IN_PROGRESS.
 *    On restart, the detector starts again and catches up. The maximum stuck time
 *    is: detector crash duration + heartbeatTtlMs + detectorInterval.
 *    In production, run two detector instances with Kubernetes pod anti-affinity
 *    to ensure at least one is always running.
 *
 * INTERVIEW Q: "How does this compare to Kubernetes liveness probes?"
 * A: Kubernetes liveness probes restart a crashed pod. But they don't know about
 *    application-level job state. If a worker crashes mid-job, Kubernetes restarts
 *    the pod but the DB still shows the job as IN_PROGRESS. The FailoverDetector
 *    is an APPLICATION-LEVEL health mechanism complementing Kubernetes.
 *    Both layers are needed: Kubernetes handles process crashes, FailoverDetector
 *    handles job state recovery.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FailoverDetector {

    private final JobRepository       jobRepository;
    private final HeartbeatService    heartbeatService;
    private final OptiQueueProperties props;

    // ── FAILOVER SCAN ─────────────────────────────────────────────────────

    /**
     * Main failover scan — runs every 30 seconds.
     *
     * Finds IN_PROGRESS jobs older than the heartbeat TTL and checks
     * if their assigned worker is still alive via Redis heartbeat.
     *
     * initialDelay=30000: Give the system 30s to fully start before scanning.
     * Without this, a fresh startup might falsely detect jobs from the
     * previous deployment's crash as "stuck" before heartbeats are established.
     */
    @Scheduled(fixedDelayString = "${failover.scan-interval-ms:30000}",
               initialDelay    = 30000)
    @Transactional
    public void detectAndRecoverFailedWorkers() {
        long heartbeatTtlMs = props.getHeartbeat().getTtlMs();

        // Look for jobs that have been IN_PROGRESS longer than the TTL
        // These MIGHT be from dead workers
        Instant stuckBefore = Instant.now().minusMillis(heartbeatTtlMs);
        List<Job> stuckJobs = jobRepository.findStuckInProgressJobs(stuckBefore);

        if (stuckJobs.isEmpty()) {
            log.debug("Failover scan: no stuck IN_PROGRESS jobs found");
            return;
        }

        log.info("Failover scan: found {} potentially stuck IN_PROGRESS jobs", stuckJobs.size());

        int recovered = 0;
        int skipped   = 0;

        for (Job job : stuckJobs) {
            if (isWorkerDead(job.getAssignedWorkerId())) {
                recoverJob(job);
                recovered++;
            } else {
                log.debug("Job {} (worker={}) is still alive — skipping",
                    job.getId(), job.getAssignedWorkerId());
                skipped++;
            }
        }

        log.info("Failover scan complete: recovered={}, skipped (worker alive)={}",
            recovered, skipped);
    }

    // ── WORKER LIVENESS CHECK ─────────────────────────────────────────────

    /**
     * Returns true if the worker is definitively dead (heartbeat key expired).
     *
     * Three-state logic:
     *   - workerId == null → job was never fully claimed → treat as dead → recover
     *   - heartbeat key exists → worker is alive → leave it
     *   - heartbeat key missing → worker is dead → recover
     */
    private boolean isWorkerDead(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            // Job is IN_PROGRESS but has no worker ID — data inconsistency
            // (e.g., crashed during the claim step). Recover it.
            return true;
        }

        boolean alive = heartbeatService.isWorkerAlive(workerId);

        if (!alive) {
            log.warn("Worker {} appears dead (heartbeat key expired)", workerId);
        }

        return !alive;
    }

    // ── JOB RECOVERY ──────────────────────────────────────────────────────

    /**
     * Resets a stuck IN_PROGRESS job back to PENDING.
     *
     * We do NOT increment retryCount here. Why?
     *   The job didn't actually fail — the WORKER failed. The job itself
     *   never had a chance to run (or was running successfully and got cut off).
     *   Incrementing retryCount would waste a retry attempt on what is
     *   essentially a infrastructure failure, not a job failure.
     *
     * Contrast with WorkerPool.executeJob() failure path:
     *   There, retryCount IS incremented because the job code itself threw an exception.
     *
     * The distinction: INFRASTRUCTURE failure vs APPLICATION failure.
     *
     * INTERVIEW Q: "Could the same job be recovered multiple times?"
     * A: Theoretically yes — if the recovery transaction commits but the job
     *    is still somehow IN_PROGRESS in a stale cache. Our mitigations:
     *    1. @Modifying(clearAutomatically=true) on bulk updates forces JPA cache flush
     *    2. Workers re-check status from DB before claiming (Step 5 WorkerPool)
     *    3. DB-level: the assignedWorkerId gets nulled — a new worker will stamp their ID
     *    So at worst, two workers briefly think they own the job, but only one
     *    succeeds in the DB claim (due to the status check in executeJob).
     */
    private void recoverJob(Job job) {
        log.warn("Recovering stuck job {} (was assigned to dead worker {})",
            job.getId(), job.getAssignedWorkerId());

        String previousWorker = job.getAssignedWorkerId();

        // Reset the job state
        job.setStatus(JobStatus.PENDING);
        job.setAssignedWorkerId(null);     // No longer owned by the dead worker
        job.setStartedAt(null);            // Clear the stale start time
        job.setLastError("Recovered from dead worker: " + previousWorker);
        job.setNextRetryAt(null);

        jobRepository.save(job);

        log.info("Job {} reset to PENDING (recovered from worker {})",
            job.getId(), previousWorker);
    }

    // ── STARTUP RECOVERY ──────────────────────────────────────────────────

    /**
     * Runs once at startup to recover jobs from the PREVIOUS deployment's crash.
     *
     * Scenario: the entire app was killed (docker stop, server restart).
     * On next startup, there may be jobs stuck in IN_PROGRESS with worker IDs
     * that no longer exist. This method clears them all on boot.
     *
     * IMPORTANT: Only run this when starting fresh, not during rolling deploys
     * where OTHER instances might legitimately be running those jobs.
     * Use the environment variable STARTUP_RECOVERY_ENABLED=true to opt in.
     *
     * Alternative: use Flyway migration to reset IN_PROGRESS → PENDING on startup.
     * That's even simpler but you lose the ability to inspect what was running.
     */
    @Transactional
    public int recoverStuckJobsAtStartup() {
        // Only recover jobs where NO heartbeat exists for the assigned worker.
        // If a rolling deploy is happening, alive workers still have heartbeats.
        Instant stuckBefore = Instant.now().minusSeconds(60); // Stuck for > 1 minute
        List<Job> stuckJobs = jobRepository.findStuckInProgressJobs(stuckBefore);

        int recovered = 0;
        for (Job job : stuckJobs) {
            if (isWorkerDead(job.getAssignedWorkerId())) {
                recoverJob(job);
                recovered++;
            }
        }

        if (recovered > 0) {
            log.info("Startup recovery: reset {} stuck IN_PROGRESS jobs to PENDING", recovered);
        }

        return recovered;
    }
}
