# Distributed Rate Limiting & Abuse Prevention

This document explains the rate limiting architecture implemented in OptiQueue via `RedisRateLimiter.java`, designed to protect queue ingestion pipelines and background workers against burst flooding and abusive traffic.

---

## 1. Problem Statement

Distributed queuing systems face unique challenges under unthrottled traffic:
- **Worker Saturation**: A burst of millions of low-priority jobs can exhaust thread pools, starving mission-critical high-priority tasks.
- **Database Connection Starvation**: Excessive concurrent API insertions exhaust connection pools.
- **Memory Pressure**: Unbounded queuing can overwhelm in-memory heap structures.

---

## 2. Algorithm Comparison

| Algorithm | Pros | Cons | OptiQueue Usage |
|---|---|---|---|
| **Fixed Window Counter** | Simple, fast, $O(1)$ memory per key | Burst at boundary edges can allow $2\times$ limit | Used with short (60s) windows |
| **Sliding Window Log** | 100% accurate boundaries | $O(N)$ memory per request | High memory overhead in Redis |
| **Token Bucket** | Smooth rate, accommodates controlled bursts | Requires fractional timestamp math | Integrated via Redis atomic scripts |

---

## 3. OptiQueue Redis Implementation

OptiQueue uses an atomic Redis Lua script pattern for distributed, cluster-safe rate tracking:

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
```

### 3.1 Key Design & Isolation
- **Key Pattern**: `optiqueue:ratelimit:<clientId>:<windowBucket>`
- **Client Identification**:
  - Authenticated API requests: Hashed `X-API-KEY` header
  - Unauthenticated / Public requests: Client Remote IP Address (`X-Forwarded-For` or `HttpServletRequest.getRemoteAddr()`)
- **Window Bucket**: `epochSeconds / windowSizeSeconds`
  - Grouping requests into deterministic integer buckets ensures that multiple application instances naturally share identical counter keys in Redis without cluster coordination.

### 3.2 Automatic Memory Eviction
Keys automatically expire via Redis `EXPIRE` after the window elapses (`windowSec + 5` grace period). Redis cleans expired counters from memory without manual garbage collection.

---

## 4. HTTP Headers & Contract

Responses from `/api/jobs` include rate-limiting telemetry headers:

| Header | Description | Example |
|---|---|---|
| `X-RateLimit-Limit` | Maximum allowed submissions per window | `100` |
| `X-RateLimit-Remaining` | Remaining allowed quota in active window | `84` |
| `X-RateLimit-Reset` | Epoch seconds when the current window resets | `1730419200` |

### Throttled Response (HTTP 429)
When a client exceeds quota:
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 42

{
  "error": "Too Many Requests",
  "message": "Rate limit quota exceeded. Please retry after window reset.",
  "status": 429,
  "timestamp": "2026-10-31T21:45:00Z"
}
```
