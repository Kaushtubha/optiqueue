package com.optiqueue.redis;

import com.optiqueue.config.OptiQueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * HeartbeatService — worker liveness tracking via Redis TTL keys.
 *
 * Each worker node writes a heartbeat key to Redis every N seconds.
 * The key has a TTL of N × 3 (3 missed heartbeats = declared dead).
 *
 * KEY DESIGN:
 *   optiqueue:heartbeat:{workerId}  →  value: timestamp  TTL: heartbeatTtlMs
 *
 * WHY Redis TTL instead of DB timestamps?
 *   DB approach: worker writes UPDATE heartbeat_at = now() to a DB table every Ns.
 *     Failover check: SELECT * FROM workers WHERE heartbeat_at < now() - interval.
 *     Problem: requires a DB write every N seconds per worker (expensive), and
 *     a scheduled DB read to detect dead workers (more queries).
 *
 *   Redis TTL approach: worker writes to Redis every Ns.
 *     Failover check: EXISTS optiqueue:heartbeat:{workerId}
 *     If the key doesn't exist → worker is dead (TTL expired on its own).
 *     No explicit "mark as dead" step needed — TTL handles it automatically.
 *
 * TTL GUARANTEE:
 *   If the worker process crashes (kill -9, OOM, power loss), it stops
 *   sending heartbeats. Redis TTL expires after heartbeatTtlMs.
 *   The failover detector (Step 10) checks for missing heartbeat keys.
 *   MAX time to detect a dead worker: heartbeatTtlMs (e.g., 15 seconds).
 *
 * INTERVIEW Q: "Why not use Java's ScheduledExecutorService for heartbeats?"
 * A: The heartbeat WRITE can use either @Scheduled or ScheduledExecutorService —
 *    both work. The key difference is the READ side. Redis TTL auto-expires the
 *    key when the worker dies — no other process needs to actively mark it dead.
 *    With a DB + ScheduledExecutorService, you need an extra cleanup job.
 *    Redis gives you "auto-cleanup on death" for free via TTL.
 *
 * INTERVIEW Q: "What's the minimum heartbeat interval for safe failover detection?"
 *   The interval is a tradeoff:
 *   - Shorter interval → faster failure detection, more Redis writes
 *   - Longer interval → slower detection, fewer writes
 *   Common values: heartbeat every 5s, TTL = 15s (3x interval).
 *   3× gives 2 missed heartbeats grace before declaring death (handles GC pauses).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HeartbeatService {

    private final StringRedisTemplate redisTemplate;
    private final OptiQueueProperties props;

    // Heartbeat key prefix
    private static final String HEARTBEAT_PREFIX = "optiqueue:heartbeat:";

    // ── HEARTBEAT WRITE ───────────────────────────────────────────────────

    /**
     * Sends a heartbeat for this worker node.
     * Called every heartbeat.interval-ms by @Scheduled.
     *
     * Writes the current timestamp to the heartbeat key and refreshes TTL.
     * The TTL refresh is critical: if we only set the TTL on first write,
     * the key would expire regardless of continued heartbeats.
     *
     * SET optiqueue:heartbeat:{workerId} {timestamp} PX {ttlMs}
     * This is a single atomic command (no NX — we WANT to overwrite).
     */
    @Scheduled(fixedDelayString = "${optiqueue.heartbeat.interval-ms:5000}",
               initialDelay    = 2000)
    public void sendHeartbeat() {
        String workerId = getWorkerId();
        String key      = HEARTBEAT_PREFIX + workerId;
        long   ttlMs    = props.getHeartbeat().getTtlMs();
        String value    = String.valueOf(System.currentTimeMillis());

        try {
            // SET with TTL — refreshes TTL on every heartbeat
            redisTemplate.opsForValue().set(key, value, ttlMs, TimeUnit.MILLISECONDS);
            log.debug("Heartbeat sent: workerId={}, ttl={}ms", workerId, ttlMs);

        } catch (Exception e) {
            // Redis unavailable — log warning but don't crash the worker.
            // Worker continues processing jobs; failover detector will notice
            // the missing heartbeat eventually.
            log.warn("Heartbeat failed (Redis unavailable): {}", e.getMessage());
        }
    }

    // ── HEARTBEAT READ ────────────────────────────────────────────────────

    /**
     * Checks if a specific worker is alive (its heartbeat key exists in Redis).
     *
     * @param workerId the worker ID to check
     * @return true if the worker has a valid unexpired heartbeat
     */
    public boolean isWorkerAlive(String workerId) {
        try {
            Boolean exists = redisTemplate.hasKey(HEARTBEAT_PREFIX + workerId);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Cannot check worker heartbeat (Redis unavailable): {}", e.getMessage());
            // If Redis is down, assume workers are alive to avoid false failover
            return true;
        }
    }

    /**
     * Returns the last heartbeat timestamp for a worker (ms since epoch).
     * Returns -1 if the worker has no heartbeat (dead or never started).
     */
    public long getLastHeartbeatMs(String workerId) {
        try {
            String value = redisTemplate.opsForValue().get(HEARTBEAT_PREFIX + workerId);
            if (value == null) return -1L;
            return Long.parseLong(value);
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Returns remaining TTL in milliseconds for a worker's heartbeat key.
     * Used by monitoring endpoints to show "time until declared dead".
     * Returns -1 if key doesn't exist, -2 if key has no TTL (shouldn't happen).
     */
    public long getHeartbeatTtlMs(String workerId) {
        try {
            Long ttl = redisTemplate.getExpire(HEARTBEAT_PREFIX + workerId, TimeUnit.MILLISECONDS);
            return ttl != null ? ttl : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Explicitly removes the heartbeat key (called during graceful shutdown).
     * Without this, the key lingers until TTL expiry — delaying other nodes from
     * detecting the worker is gone and taking over its jobs.
     *
     * On graceful shutdown (SIGTERM):
     *   1. @PreDestroy in WorkerPool sets running=false
     *   2. HeartbeatService.removeHeartbeat() called in that same @PreDestroy
     *   3. Redis key deleted immediately → failover can begin at once
     *
     * On crash (SIGKILL):
     *   1. @PreDestroy doesn't run (JVM killed)
     *   2. Redis key expires after TTL (15s)
     *   3. Failover detects dead worker after TTL
     */
    public void removeHeartbeat() {
        String workerId = getWorkerId();
        try {
            redisTemplate.delete(HEARTBEAT_PREFIX + workerId);
            log.info("Heartbeat key removed for worker: {}", workerId);
        } catch (Exception e) {
            log.warn("Failed to remove heartbeat key: {}", e.getMessage());
        }
    }

    // ── HELPER ────────────────────────────────────────────────────────────

    private String getWorkerId() {
        return System.getenv().getOrDefault("WORKER_ID",
            "worker-" + ProcessHandle.current().pid());
    }
}
