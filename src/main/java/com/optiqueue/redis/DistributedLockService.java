package com.optiqueue.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Distributed lease and mutual-exclusion coordinator backed by Redis.
 * <p>
 * Ensures that multi-node cluster deployments coordinate scheduler cycles without concurrent execution races.
 * Employs atomic SETNX leases with auto-expiring TTLs and Lua script release verification.
 */
@Component
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * Constructs a new DistributedLockService with the underlying Redis template.
     *
     * @param redisTemplate template used for distributed lock key operations
     */
    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static final String SCHEDULER_LOCK_KEY = "optiqueue:scheduler:lock";
    public static final long DEFAULT_TTL_MS = 10000L;
    private static final String UNLOCK_LUA_SCRIPT = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """;

    private static final org.springframework.data.redis.core.script.RedisScript<Long> UNLOCK_SCRIPT =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(UNLOCK_LUA_SCRIPT, Long.class);

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
        return releaseLock(SCHEDULER_LOCK_KEY, holderId);
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

    boolean releaseLock(String lockKey, String holderId) {
        try {
            Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                java.util.Collections.singletonList(lockKey),
                holderId
            );
            boolean released = result != null && result > 0;
            if (released) {
                log.debug("Distributed lock {} atomically released by {}", lockKey, holderId);
            } else {
                log.debug("Distributed lock {} not released by {} (lock expired or held by another node)", lockKey, holderId);
            }
            return released;
        } catch (Exception e) {
            log.warn("Atomic lock release failed for key {}: {}", lockKey, e.getMessage());
            return false;
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
