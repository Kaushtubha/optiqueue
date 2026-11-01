# Cloud Deployment Architecture Guide

This document outlines production reference architectures for deploying OptiQueue on Kubernetes (EKS, GKE, AKS) and managed cloud infrastructure providers (AWS, GCP).

---

## 1. Cloud Reference Topology

```
                         [ Cloudflare / Route 53 DNS ]
                                       |
                                       v
                         [ Application Load Balancer ]
                               (TLS Termination)
                                       |
                      +----------------+----------------+
                      |                                 |
                      v                                 v
             [ OptiQueue Pod 1 ]               [ OptiQueue Pod 2 ]
             (API + Worker Pool)               (API + Worker Pool)
                      |                                 |
          +-----------+-----------+         +-----------+-----------+
          |                       |         |                       |
          v                       v         v                       v
+-------------------+   +-----------------------------------------------+
| Managed Redis     |   | Managed PostgreSQL                            |
| (AWS ElastiCache /|   | (AWS Aurora / GCP Cloud SQL Multi-AZ)         |
| GCP Memorystore)  |   |                                               |
| - Distributed Lock|   | - Authoritative Job State Persistence         |
| - Worker Liveness |   | - Read Replicas for Analytics/Reporting       |
+-------------------+   +-----------------------------------------------+
```

---

## 2. Kubernetes Deployment Specification

### 2.1 Pod Resource Recommendations
- **CPU**: Request `500m`, Limit `2000m`
- **Memory**: Request `1Gi`, Limit `2Gi` (configured with `-XX:MaxRAMPercentage=75.0`)

### 2.2 Health Probes
OptiQueue integrates with Spring Boot Actuator health endpoints:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
  failureThreshold: 2
```

---

## 3. Horizontal Pod Autoscaling (HPA) by Queue Depth

Scaling background queue consumers strictly by CPU utilization is often ineffective (workers waiting on network I/O may have low CPU usage despite massive queue backlogs).

OptiQueue enables autoscaling directly based on custom Prometheus metrics via KEDA (Kubernetes Event-driven Autoscaling) or Prometheus Adapter:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: optiqueue-scaler
spec:
  scaleTargetRef:
    name: optiqueue-deployment
  minReplicaCount: 2
  maxReplicaCount: 20
  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://prometheus-k8s.monitoring:9090
        metricName: optiqueue_queue_depth
        query: sum(optiqueue_queue_depth)
        threshold: '100'
```
*Autoscaler adds worker pods whenever pending queue backlog exceeds 100 jobs per active replica.*

---

## 4. Managed Services Configuration Checklist

1. **Redis**:
   - Enable `appendonly yes` (AOF) for crash durability.
   - Configure eviction policy to `noeviction` (rate limiting and heartbeats manage their own TTLs; unexpected eviction could break lease tracking).
2. **PostgreSQL**:
   - Connection Pooling: Deploy PgBouncer or configure HikariCP `maximum-pool-size: 20` per instance.
   - Multi-AZ failover with synchronous standbys.
