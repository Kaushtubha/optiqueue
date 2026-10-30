# Security Policy

OptiQueue takes the security of distributed task execution and queuing infrastructure seriously. This document describes our security practices, threat model, and vulnerability reporting procedures.

---

## 1. Supported Versions

| Version | Supported |
|---|---|
| `1.0.x` | :white_check_mark: Active security patches |
| `< 1.0` | :x: End of life |

---

## 2. Reporting a Vulnerability

If you discover a security vulnerability within OptiQueue, please report it privately. **Do not create public GitHub issues for security vulnerabilities.**

### Disclosure Process
1. Email your findings directly to **kaushtubhamshukla@gmail.com**.
2. Include the following details:
   - Component / class affected (e.g. `ApiKeyAuthFilter`, `DistributedLockService`)
   - Proof of Concept (PoC) or reproduction steps
   - Potential impact and threat severity assessment
3. You will receive an acknowledgment within 48 hours.
4. Once verified, a patch will be authored, tested, and released in an expedited patch update.

---

## 3. Threat Model & Mitigations

### 3.1 Authentication & Authorization
- **Mechanism**: Enforced via `ApiKeyAuthFilter` inspecting the `X-API-KEY` request header.
- **Protection**: Unauthenticated requests are rejected with `401 Unauthorized` prior to reaching business logic or queue storage.

### 3.2 Denial of Service (DoS) & Abuse
- **Mechanism**: Distributed Token Bucket rate limiting via `RedisRateLimiter`.
- **Protection**: Clients exceeding configured thresholds are throttled with `429 Too Many Requests` status codes, protecting thread pools and backend databases from resource exhaustion.

### 3.3 Data Injection & Deserialization
- **Mechanism**: Payload input validation using Jakarta `@Valid` annotations and strict JSON string handling.
- **Protection**: Untrusted payloads are treated as opaque text strings and validated against handler-specific schemas before execution.

### 3.4 Distributed Lock Safety
- **Mechanism**: Redis distributed locks with deterministic TTLs and atomic Lua script releases.
- **Protection**: Prevents split-brain state mutations and accidental release of expired lock leases by slow or preempted worker threads.

### 3.5 Network Security & Transport Encryption
- **Redis Communication**: Configurable TLS support via `REDIS_SSL=true`.
- **Database Communication**: PostgreSQL SSL mode enabled for cloud deployments.
- **Secrets Management**: Database passwords, Redis credentials, and API keys are passed strictly via runtime environment variables and excluded from source control.
