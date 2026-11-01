# Distributed Deduplication & Idempotency Semantics

This document details the deduplication architecture, idempotency key mechanics, and exactly-once execution guarantees in OptiQueue.

---

## 1. The Distributed Systems Challenge

In distributed architectures, networks are inherently unreliable. Transient timeouts, client retries, and network packet retransmissions frequently lead to duplicate requests.

Queuing systems typically provide **At-Least-Once Delivery** guarantees. To prevent duplicate side effects (e.g., charging a customer twice, sending duplicate emails, double-processing invoices), OptiQueue couples client-supplied idempotency keys with database-enforced ACID constraints.

---

## 2. OptiQueue Idempotency Protocol

```
Client Request (POST /api/jobs)
[ idempotencyKey = "tx-99482" ]
               |
               v
     +-------------------+
     |    JobService     |
     +-------------------+
               |
               | INSERT INTO jobs (..., idempotency_key)
               v
     +-------------------+
     |    PostgreSQL     |
     +-------------------+
               |
       +-------+-------+
       |               |
 [Key is New]     [Key Exists (Conflict)]
       |               |
       v               v
  Insert OK      Catch DataIntegrityViolationException
 Return 201      SELECT existing job record
                 Return 200 OK (Existing Job JSON)
```

### 2.1 Database Unique Constraint
OptiQueue enforces uniqueness at the storage engine level:
```sql
ALTER TABLE jobs ADD CONSTRAINT uk_jobs_idempotency UNIQUE (idempotency_key);
```
Application-level checks alone (e.g. `findByKey()`) suffer from race conditions under high concurrent load (Time-of-Check to Time-of-Use / TOCTOU bugs). Database unique constraints guarantee atomicity across clustered nodes.

### 2.2 Client-Facing API Response Contract
- **First Submission**: Returns HTTP `201 Created` with the newly assigned UUID and `PENDING` status.
- **Subsequent Identical Submissions**: Returns HTTP `200 OK` containing the identical job record, reflecting its current lifecycle status (`IN_PROGRESS`, `COMPLETED`, etc.) without altering state or re-enqueuing.

---

## 3. Best Practices for Idempotency Keys

1. **Client Generation**: Clients should use deterministic, domain-specific identifiers (e.g. `order-uuid-charge`, `invoice-1029-reminder`) or a Version 4 UUID.
2. **Scope**: Keep idempotency keys unique per logical business operation.
3. **Downstream Safety**: Task handlers that interact with external third-party payment gateways or SMS APIs should pass the `idempotencyKey` as an external reference to achieve end-to-end idempotent processing.
