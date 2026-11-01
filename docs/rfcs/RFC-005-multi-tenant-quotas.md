# RFC-005: Multi-Tenant Quotas & Tiered Rate Limiting

- **Status**: Proposed
- **Author**: OptiQueue Core Team
- **Date**: 2026-11-02

---

## 1. Summary
Proposes configurable per-tenant tier limits (`FREE`, `STANDARD`, `ENTERPRISE`) mapped to API keys, regulating ingestion requests/minute and maximum concurrent in-flight jobs.
