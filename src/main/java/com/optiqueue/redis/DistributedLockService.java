package com.optiqueue.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * DistributedLockService — advisory distributed lock using Redis SET NX PX.
 *
 * Used by SchedulerService to ensure only ONE scheduler node polls the DB
 * at a time in a multi-node deployment. Without this, two scheduler nodes
 * could concurrently fetch the same 8 PENDING jobs and submit them to their
 * respective local blocking queues — causing the same job to be executed twice.
 *
 * THE LOCK COMMAND:
 *   SET optiqueue:scheduler:lock {holderId} NX PX {ttlMs}
 *
 *   NX  = set only if key does Not eXist (atomic conditional set)
 *   PX  = set expiry in milliseconds
 *   {holderId} = unique value per node/thread (UUID) — used to verify ownership before release
 *
 *   This is a single atomic command. The old SETNX + EXPIRE two-step had a race:
 *   if the process crashed between SETNX and EXPIRE, the lock was held forever.
 *   SET NX PX eliminates this race — TTL is set atomically with the lock acquisition.
 *
 * FENCING TOKEN (production extension):
 *   In distributed systems, even with TTL you can have a "split-brain" problem:
 *   - Node A acquires lock, starts slow operation
 *   - Lock TTL expires (A was too slow)
 *   - Node B acquires lock, starts operation
 *   - Node A finishes, writes to DB — now both A and B wrote!
 *   Solution: use a monotonically increasing fencing token (e.g., Redis INCR).
 *   DB writes include the token; DB rejects writes with a stale token.
 *   This is the Redlock algorithm extension for strong consistency.
 *
 * INTERVIEW Q: "What is Redlock? When would you use it?"
 * A: Redlock is an algorithm for distributed locks across N independent Redis nodes
 *    (not replicas, not cluster — standalone instances). A lock is acquired by
 *    writing to a majority (N/2 + 1) of nodes within a time bound. This survives
 *    the failure of up to N/2 nodes. For a single Redis node, SET NX PX is sufficient.
 *    Redlock adds complexity — only use it when single-node Redis is insufficient.
 *
 * INTERVIEW Q: "Is a Redis-based distributed lock safe with Redis Sentinel?"
 * A: No — Redis Sentinel uses async replication. If the master crashes after acquiring
 *    the lock but before replicating to a replica, a new master is promoted without
 *    the lock key. Two nodes could acquire the lock simultaneously. For strong
 *    consistency, use Redlock (multiple Redis nodes) or a CP system like ZooKeeper.
 *    For our use case (scheduler coordination, not financial transactions), the
 *    single-node lock is acceptable — worst case is a duplicate DB poll, which
 *    the DB's PENDING status check handles gracefully.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    private static final String SCHEDULER_LOCK_KEY = "optiqueue:scheduler:lock";
    private static final long   DEFAULT_TTL_MS      = 10_000; // 10 seconds

    // ── ACQUIRE ───────────────────────────────────────────────────────────

    /**
     * Attempts to acquire the scheduler lock.
     *
     * @param holderId unique identifier for the caller (workerId from WorkerPool)
     * @param ttlMs    lock TTL in milliseconds — should be > max expected scheduler cycle time
     * @return true if lock acquired, false if another node holds it
     */
    public boolean tryAcquireSchedulerLock(String holderId, long ttlMs) {
        try {
            // SET key value NX PX ttlMs
            // Returns true if key was set (we got the lock), false if key existed
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(SCHEDULER_LOCK_KEY, holderId, ttlMs, TimeUnit.MILLISECONDS);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Scheduler lock acquired by: {} (TTL: {}ms)", holderId, ttlMs);
                return true;
            } else {
                // Another node holds the lock — check who
                String currentHolder = redisTemplate.opsForValue().get(SCHEDULER_LOCK_KEY);
                log.debug("Scheduler lock held by: {} — skipping this tick", currentHolder);
                return false;
            }

        } catch (Exception e) {
            // Redis is unavailable — fail open: allow this node to proceed
            // without the lock. Worst case: two nodes poll DB concurrently.
            // The job state machine and DB constraints handle this gracefully.
            log.warn("Redis lock check failed (Redis unavailable): {} — proceeding without lock",
                e.getMessage());
            return true;
        }
    }

    /** Convenience method using default TTL */
    public boolean tryAcquireSchedulerLock(String holderId) {
        return tryAcquireSchedulerLock(holderId, DEFAULT_TTL_MS);
    }

    // ── RELEASE ───────────────────────────────────────────────────────────

    /**
     * Releases the scheduler lock — only if this node still holds it.
     *
     * WHY check holderId before deleting?
     *   If our TTL expired and another node acquired the lock, we must NOT
     *   delete their lock. Without this check:
     *     1. Node A acquires lock (holderId=A)
     *     2. Node A is slow — TTL expires
     *     3. Node B acquires lock (holderId=B)
     *     4. Node A finishes, calls release() — deletes B's lock!
     *     5. Node C acquires lock — now A and B and C are all running concurrently!
     *
     * The holderId check prevents this. The check + delete are NOT atomic with
     * StringRedisTemplate — for production, use a Lua script:
     *
     *   if redis.call('GET', KEYS[1]) == ARGV[1] then
     *     return redis.call('DEL', KEYS[1])
     *   else
     *     return 0
     *   end
     *
     * @param holderId must match the holderId used in tryAcquire
     * @return true if lock was released, false if we didn't hold it
     */
    public boolean releaseSchedulerLock(String holderId) {
        try {
            String currentHolder = redisTemplate.opsForValue().get(SCHEDULER_LOCK_KEY);

            if (holderId.equals(currentHolder)) {
                redisTemplate.delete(SCHEDULER_LOCK_KEY);
                log.debug("Scheduler lock released by: {}", holderId);
                return true;
            } else {
                log.warn("Lock release attempted by {} but current holder is {} — lock expired?",
                    holderId, currentHolder);
                return false;
            }

        } catch (Exception e) {
            log.warn("Redis lock release failed: {}", e.getMessage());
            return false;
        }
    }

    // ── GENERIC LOCK (reusable for other resources) ───────────────────────

    /**
     * General-purpose try-lock for any key.
     * Used in tests and for future extension points.
     *
     * @param lockKey  the Redis key to use as the lock
     * @param holderId value to store (identifies who holds the lock)
     * @param ttlMs    lock TTL
     * @return a LockHandle — call release() on it when done
     */
    public LockHandle tryLock(String lockKey, String holderId, long ttlMs) {
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, holderId, ttlMs, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("Lock acquisition failed for key {}: {}", lockKey, e.getMessage());
            acquired = false;
        }
        return new LockHandle(lockKey, holderId, acquired, this);
    }

    void releaseLock(String lockKey, String holderId) {
        try {
            String current = redisTemplate.opsForValue().get(lockKey);
            if (holderId.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Lock release failed for key {}: {}", lockKey, e.getMessage());
        }
    }

    // ── LOCK HANDLE ───────────────────────────────────────────────────────

    /**
     * AutoCloseable handle — supports try-with-resources for safe lock release:
     *
     *   try (LockHandle lock = lockService.tryLock("myKey", holderId, 5000)) {
     *       if (lock.isAcquired()) {
     *           // critical section
     *       }
     *   } // lock.close() called automatically — guaranteed release
     *
     * This pattern ensures the lock is ALWAYS released even if the critical
     * section throws an exception. Without try-with-resources, forgetting to
     * release in a finally block is a common lock-leak bug.
     */
    public record LockHandle(String lockKey, String holderId, boolean acquired,
                              DistributedLockService service) implements AutoCloseable {

        public boolean isAcquired() { return acquired; }

        @Override
        public void close() {
            if (acquired) {
                service.releaseLock(lockKey, holderId);
            }
        }
    }
}
