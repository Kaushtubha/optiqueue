# RFC-004: Recurring Cron Job Scheduling Engine

- **Status**: Proposed
- **Author**: OptiQueue Core Team
- **Date**: 2026-11-02

---

## 1. Motivation
Allow clients to schedule recurring jobs using standard 5-part cron expressions (e.g. `0 0 * * *` for daily midnight runs).

---

## 2. Design
- Add `cron_expression` and `next_run_at` to `schedules` table.
- Cron evaluator calculates the next execution epoch using Spring `CronExpression`.
- Single-execution jobs are stamped out atomically upon trigger.
