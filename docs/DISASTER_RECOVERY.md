# Disaster Recovery & Multi-Region Resilience Strategy

This document outlines the business continuity and disaster recovery (DR) architecture for OptiQueue deployments.

---

## 1. Disaster Recovery Objectives

- **Recovery Point Objective (RPO)**: $< 1\text{ minute}$ of data loss.
- **Recovery Time Objective (RTO)**: $< 5\text{ minutes}$ to achieve full queue operational processing.

---

## 2. Multi-Region Topologies

### Active-Passive Cold/Warm Standby
- Primary region handles 100% of API submissions and worker execution.
- Secondary region maintains an Aurora/Cloud SQL cross-region read replica and passive standby deployment.
- If primary region fails, DNS failover (Route 53 Health Checks) routes traffic to secondary, promoting replica to primary writer.

### Split-Brain Prevention
Distributed locks in Redis do not cross region boundaries without high latency penalties. Each region maintains local Redis clusters; database unique constraints guarantee idempotency across region failovers.
