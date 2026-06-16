package com.optiqueue.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * RedisRateLimiter — fixed-window rate limiter using Redis INCR + EXPIRE.
 *
 * ALGORITHM (fixed window):
 *   Key format: "rate:{clientId}:{windowStart}"
 *   windowStart = epoch_second / windowSeconds  (all requests in the same
 *                                                 second-bucket share a key)
 *
 *   On each request:
 *     1. INCR key          → atomic increment, returns new count
 *     2. If count == 1:    → first request in this window → SET EXPIRE
 *        (sets TTL only on first request to avoid resetting the window)
 *     3. If count > limit → REJECTED (429 Too Many Requests)
 *     4. Else             → ALLOWED
 *
 * ATOMICITY:
 *   INCR is an atomic Redis command — no two clients can get the same count.
 *   The INCR + EXPIRE are NOT atomic with each other. A crash between them
 *   could leave the key without an expiry (memory leak). Production fix:
 *   use a Lua script to make INCR + EXPIRE atomic in a single round trip:
 *
 *     local count = redis.call('INCR', KEYS[1])
 *     if count == 1 then
 *       redis.call('EXPIRE', KEYS[1], ARGV[1])
 *     end
 *     return count
 *
 *   We show both approaches below (simple and Lua-based).
 *
 * INTERVIEW Q: "What's the Redis memory impact of rate limiting?"
 * A: Each unique clientId gets one key per time window. Key expires automatically
 *    when the window closes. Memory usage = O(unique clients) at any moment.
 *    For 10,000 active clients with 60s windows: ~10,000 keys × ~60 bytes ≈ 600KB.
 *    Trivial. Redis handles tens of millions of keys comfortably.
 *
 * INTERVIEW Q: "How does INCR prevent race conditions without a mutex?"
 * A: Redis is single-threaded for command execution. All commands are queued
 *    and executed sequentially. INCR is processed atomically — no two clients
 *    ever see the same intermediate state. This is Redis's core concurrency model:
 *    single-threaded event loop, no locks needed for individual commands.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    // Default limits — overridable per call
    private static final int    DEFAULT_LIMIT_PER_WINDOW = 100; // requests
    private static final long   DEFAULT_WINDOW_SECONDS   = 60;  // 1 minute window

    // ── SIMPLE FIXED WINDOW ───────────────────────────────────────────────

    /**
     * Checks if a client (identified by clientId, e.g. IP or API key) is
     * within their rate limit for the current time window.
     *
     * @param clientId  unique identifier (IP address, API key, userId)
     * @param limit     max requests allowed per window
     * @param windowSec window duration in seconds
     * @return RateLimitResult with allowed flag and current count
     */
    public RateLimitResult checkLimit(String clientId, int limit, long windowSec) {
        // Compute current window bucket: all requests in same 60s block share this key
        long windowStart = System.currentTimeMillis() / 1000 / windowSec;
        String key = String.format("rate:%s:%d", clientId, windowStart);

        try {
            // INCR is atomic — returns the new value after increment
            Long count = redisTemplate.opsForValue().increment(key);

            if (count == null) {
                // Redis is unavailable — fail open (allow request) to avoid
                // blocking all traffic when Redis goes down.
                // PRODUCTION NOTE: You might choose fail-closed (reject) for
                // security-sensitive endpoints. Document this decision.
                log.warn("Redis unavailable for rate limit check on key: {}", key);
                return RateLimitResult.allowed(0, limit);
            }

            if (count == 1) {
                // First request in this window — set the TTL
                // +1 buffer so the key doesn't expire mid-window
                redisTemplate.expire(key, windowSec + 1, TimeUnit.SECONDS);
            }

            if (count > limit) {
                log.debug("Rate limit exceeded for {}: {}/{} in window {}", clientId, count, limit, windowStart);
                return RateLimitResult.rejected(count.intValue(), limit);
            }

            return RateLimitResult.allowed(count.intValue(), limit);

        } catch (Exception e) {
            log.warn("Redis rate limit check failed for {}: {} — failing open", clientId, e.getMessage());
            return RateLimitResult.allowed(0, limit);
        }
    }

    /** Convenience method using default limits */
    public RateLimitResult checkLimit(String clientId) {
        return checkLimit(clientId, DEFAULT_LIMIT_PER_WINDOW, DEFAULT_WINDOW_SECONDS);
    }

    // ── SLIDING WINDOW (Redis Sorted Set) ─────────────────────────────────

    /**
     * Sliding window rate limiter using a Redis Sorted Set.
     *
     * More accurate than fixed window but O(log n) operations.
     *
     * Algorithm:
     *   1. Remove all entries older than (now - windowMs) from the sorted set
     *   2. Count remaining entries
     *   3. If count >= limit → reject
     *   4. Else: add current timestamp to sorted set, set TTL
     *
     * @param clientId  unique identifier
     * @param requestId unique ID for this specific request (UUID)
     * @param limit     max requests allowed in the sliding window
     * @param windowMs  window duration in milliseconds
     * @return true if allowed, false if rate limited
     */
    public boolean checkSlidingWindowLimit(String clientId, String requestId,
                                           int limit, long windowMs) {
        String key   = "rate:sliding:" + clientId;
        long   now   = System.currentTimeMillis();
        long   cutoff = now - windowMs;

        try {
            // Remove timestamps older than the window
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);

            // Count current requests in window
            Long count = redisTemplate.opsForZSet().zCard(key);
            if (count != null && count >= limit) {
                return false; // Rate limited
            }

            // Add this request with its timestamp as the score
            redisTemplate.opsForZSet().add(key, requestId, now);

            // TTL = window duration (key auto-expires when no requests are left)
            redisTemplate.expire(key, Duration.ofMillis(windowMs));

            return true;

        } catch (Exception e) {
            log.warn("Sliding window rate limit check failed for {}: {} — failing open", clientId, e.getMessage());
            return true; // Fail open
        }
    }

    // ── RESULT TYPE ───────────────────────────────────────────────────────

    public record RateLimitResult(boolean allowed, int currentCount, int limit) {
        public static RateLimitResult allowed(int count, int limit) {
            return new RateLimitResult(true, count, limit);
        }
        public static RateLimitResult rejected(int count, int limit) {
            return new RateLimitResult(false, count, limit);
        }
        public int remaining() { return Math.max(0, limit - currentCount); }
    }
}
