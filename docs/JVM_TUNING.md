# JVM Garbage Collection & Container Heap Tuning Guide

This guide details recommended HotSpot JVM flags and container resource tuning for OptiQueue under heavy load.

---

## 1. Container Memory Allocation & Ergonomics

When running Java in containerized environments (Kubernetes, Docker, ECS), the JVM must respect container cgroup limits to avoid OOMKills.

```bash
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+ExplicitGCInvokesConcurrent \
  -XX:+AlwaysPreTouch"
```

---

## 2. Rationale for G1GC in OptiQueue

1. **Predictable Latencies**: OptiQueue queues background jobs with soft deadlines; pause times must remain low (<100ms) to prevent worker heartbeat expiration.
2. **Dynamic Region Allocation**: Efficiently reclaims dead payload strings after job execution without triggering full Stop-The-World (STW) pauses.
