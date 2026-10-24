package com.optiqueue.failover;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.redis.HeartbeatService;
import com.optiqueue.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class FailoverDetector {

    private static final Logger log = LoggerFactory.getLogger(FailoverDetector.class);

    private final JobRepository       jobRepository;
    private final HeartbeatService    heartbeatService;
    private final OptiQueueProperties props;

    public FailoverDetector(JobRepository jobRepository, HeartbeatService heartbeatService, OptiQueueProperties props) {
        this.jobRepository = jobRepository;
        this.heartbeatService = heartbeatService;
        this.props = props;
    }

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

        // Reset the job state via validated state machine transition
        job.transitionTo(JobStatus.PENDING);
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
