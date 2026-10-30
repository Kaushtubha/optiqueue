# OptiQueue Performance Benchmarking Guide

This guide describes how to benchmark the throughput, latency, and resource efficiency of the OptiQueue processing pipeline under simulated production workloads.

---

## 1. Benchmark Objectives & Target Metrics

The OptiQueue architecture is targeted to achieve the following performance targets:

| Metric | Target (Single Node) | Target (3-Node Cluster) |
|---|---|---|
| **Sustained Ingestion Throughput** | > 1,500 jobs/sec | > 4,000 jobs/sec |
| **P95 Submission Latency** | < 15 ms | < 25 ms |
| **P99 Submission Latency** | < 45 ms | < 60 ms |
| **Worker Dispatch Latency** | < 100 ms | < 150 ms |
| **Dead-Letter Routing Accuracy** | 100% | 100% |

---

## 2. Load Testing with k6

You can execute distributed load testing against the `/api/jobs` endpoint using [k6](https://k6.io/).

### 2.1 Benchmark Script (`benchmark-submit.js`)

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '30s', target: 200 },  // Ramp-up to 200 concurrent VUs
    { duration: '1m',  target: 500 },  // Peak sustained load
    { duration: '20s', target: 0 },    // Ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<30', 'p(99)<75'],
    http_req_failed: ['rate<0.01'], // <1% errors
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'test-api-key';

export default function () {
  const payload = JSON.stringify({
    jobType: 'BENCHMARK_TASK',
    payload: `{"iteration":${__ITER},"vu":${__VU}}`,
    priority: Math.floor(Math.random() * 4) + 1,
    idempotencyKey: `bench-${uuidv4()}`
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-KEY': API_KEY,
    },
  };

  const res = http.post(`${BASE_URL}/api/jobs`, payload, params);
  check(res, {
    'status is 201': (r) => r.status === 201,
  });

  sleep(0.01);
}
```

### 2.2 Running the Benchmark
```bash
k6 run -e TARGET_URL=http://localhost:8080 benchmark-submit.js
```

---

## 3. High-Throughput HTTP Pipelining with wrk

For raw endpoint saturation testing:
```bash
wrk -t8 -c200 -d60s \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: test-key" \
  -s scripts/wrk_post_job.lua \
  http://localhost:8080/api/jobs
```

---

## 4. Bottleneck Identification & Tuning

1. **Database Write Bottleneck**:
   - If PostgreSQL write IOPS saturated: Increase HikariCP maximum connection pool size.
   - Ensure table statistics are current: `ANALYZE jobs;`.
2. **Worker Starvation**:
   - Check if task handlers execute blocking I/O on worker threads.
   - Increase `optiqueue.worker.max-pool-size` if CPU headroom remains available.
3. **Redis Serialization Overhead**:
   - OptiQueue uses Spring Data Redis templates; verify that key TTL checks are not generating excessive network roundtrips.
