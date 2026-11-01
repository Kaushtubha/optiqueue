# RFC-002: Event-Driven Webhook Event Streams

- **Status**: Proposed
- **Author**: OptiQueue Core Team
- **Date**: 2026-11-02

---

## 1. Summary
Clients currently poll `GET /api/jobs/{id}` to determine job completion. This proposal introduces outbound webhook notifications to notify clients in real-time when a job transitions to `COMPLETED` or `DEAD_LETTER`.

---

## 2. Webhook Delivery Protocol
- Request signing via HMAC-SHA256 (`X-OptiQueue-Signature`).
- Delivery attempts managed via existing `RetryEngine` backoff.
