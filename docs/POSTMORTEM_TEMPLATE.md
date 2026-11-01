# Incident Post-Mortem Template & Example Review

This document provides the standard blameless Post-Mortem template used for documenting production outages and Sev-1 incidents in OptiQueue.

---

## 1. Incident Metadata
- **Date**: YYYY-MM-DD
- **Authors**: On-Call Engineer / Team Lead
- **Status**: [Complete / Action Items Pending]
- **Severity**: [SEV-1 / SEV-2]
- **Duration**: XX minutes

---

## 2. Executive Summary
Brief non-technical overview of customer impact, trigger event, and resolution.

---

## 3. Timeline (UTC)
- `14:00` - Alert `OptiQueueDeadLetterSpike` fires in PagerDuty.
- `14:04` - On-call engineer initiates incident bridge.
- `14:12` - Root cause identified as upstream SMS provider HTTP 503 response storm.
- `14:18` - Provider traffic restored; DLQ jobs re-queued via `scripts/dlq_manager.py`.
- `14:25` - Queue backlog clears; incident closed.

---

## 4. Root Cause Analysis (5 Whys)
1. *Why did DLQ spike?* External webhook provider returned 503 errors.
2. *Why did retries exhaust?* Backoff initial delay was too short (100ms).
3. *Why was backoff short?* Default configuration was not overridden in staging.
4. *Action Item*: Increase base backoff delay and enforce exponential jitter across all webhook handlers.
