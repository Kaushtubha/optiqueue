package com.optiqueue.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Distributed rate limiter backed by Redis.
 * <p>
 * Uses an atomic Redis Lua script to maintain per-client request counts inside bounded time windows.
 * Implements a fail-open strategy so that transient Redis outages do not disrupt core API availability.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * Constructs a RedisRateLimiter instance with the configured Redis template.
     *
     * @param redisTemplate template used for executing atomic Redis commands and Lua scripts
     */
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static final String RATE_LIMIT_LUA = """
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return count
        """;

    private static final org.springframework.data.redis.core.script.RedisScript<Long> RATE_LIMIT_SCRIPT =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    // Default limits — overridable per call
    private static final int    DEFAULT_LIMIT_PER_WINDOW = 100; // requests
    private static final long   DEFAULT_WINDOW_SECONDS   = 60;  // 1 minute window

    // ── ATOMIC FIXED WINDOW ───────────────────────────────────────────────

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
            // Atomic INCR + EXPIRE via Lua script
            Long count = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                java.util.Collections.singletonList(key),
                String.valueOf(windowSec + 1)
            );

            if (count == null) {
                log.warn("Redis unavailable for rate limit check on key: {}", key);
                return RateLimitResult.allowed(0, limit);
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
