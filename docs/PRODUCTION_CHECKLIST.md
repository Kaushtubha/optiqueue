# Production Launch Readiness Checklist

Complete this checklist prior to promoting OptiQueue to production.

---

## 1. Security & Access Control
- [ ] `security.api-keys` configured with cryptographically random tokens (not set to `disabled`)
- [ ] Database credentials loaded via Vault / AWS Secrets Manager
- [ ] Redis TLS (`REDIS_SSL=true`) enabled for cloud Redis endpoints
- [ ] Public ingress restricted strictly through API Gateway / Load Balancer

---

## 2. Capacity & Sizing
- [ ] PostgreSQL connection pool (`maximum-pool-size`) tuned against DB instance limits
- [ ] JVM memory options set with `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- [ ] Worker pool size validated against Goetz formula ($N_{threads} = N_{cpu} \times (1 + \frac{W}{C})$)

---

## 3. Telemetry & Alerts
- [ ] Prometheus metrics endpoint `/actuator/prometheus` scraping verified
- [ ] PagerDuty alerts configured for `OptiQueueDeadLetterSpike` and `OptiQueueWorkerSaturation`
- [ ] Distributed logging shipping verified in Grafana Loki / CloudWatch
