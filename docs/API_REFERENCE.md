# OptiQueue REST API Reference

The OptiQueue REST API provides programmatic access to job creation, status querying, result retrieval, cancellation, dead-letter reprocessing, and queue telemetry.

**Base Path:** `/api/jobs`  
**Default Content-Type:** `application/json`

---

## Authentication & Headers

When API key authentication is enabled, requests must include the API key header:

```http
X-API-KEY: your-configured-api-key
```

### Rate Limiting Headers
The API includes distributed rate limiting via Redis token bucket. Responses include:
- `X-RateLimit-Limit`: Maximum allowable requests per time window
- `X-RateLimit-Remaining`: Remaining request quota in the current window
- `X-RateLimit-Reset`: Time (in epoch seconds) when quota refreshes

---

## Endpoints Summary

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/jobs` | Submit a new background job (with idempotency support) |
| `GET` | `/api/jobs/{id}` | Retrieve job details and execution metadata |
| `GET` | `/api/jobs/{id}/result` | Fetch the execution result or error diagnosis |
| `DELETE` | `/api/jobs/{id}` | Cancel a queued or running job |
| `GET` | `/api/jobs` | Query jobs with optional status filter and pagination |
| `GET` | `/api/jobs/recent` | List recently executed jobs |
| `GET` | `/api/jobs/dead-letter` | List all dead-lettered jobs |
| `POST` | `/api/jobs/dead-letter/{id}/retry` | Re-queue a dead-letter job for execution |
| `GET` | `/api/jobs/metrics` | Retrieve live queue depth and throughput telemetry |
| `POST` | `/api/jobs/demo-batch` | Trigger a benchmark demo workload batch |

---

## Detailed Endpoint Documentation

### 1. Submit Job
**`POST /api/jobs`**

Enqueues a new job. If `idempotencyKey` matches an existing submission, the endpoint returns the existing record without duplicating execution (`200 OK` vs `201 Created`).

#### Request Body
```json
{
  "jobType": "EMAIL_SEND",
  "payload": "{\"recipient\":\"ops@example.com\",\"template\":\"ALERT\"}",
  "priority": 3,
  "deadline": "2026-11-01T12:00:00Z",
  "idempotencyKey": "order-10492-notification"
}
```

#### Fields
- `jobType` (String, required): Registered handler type (e.g. `EMAIL_SEND`, `WEBHOOK`, `REPORT_GEN`).
- `payload` (String, required): Task arguments encoded as JSON string or text.
- `priority` (Integer, optional): Priority tier `1` (LOW) to `4` (CRITICAL). Default is `2` (NORMAL).
- `deadline` (ISO-8601 Timestamp, optional): Completion deadline for EDF scheduling.
- `idempotencyKey` (String, optional): Unique key for deduplication.

#### Response (`201 Created` or `200 OK`)
```json
{
  "id": "7b0a884e-f495-46aa-bd08-8e6f1f4148da",
  "jobType": "EMAIL_SEND",
  "status": "QUEUED",
  "priority": 3,
  "attemptCount": 0,
  "createdAt": "2026-10-30T17:00:00Z",
  "idempotencyKey": "order-10492-notification"
}
```

---

### 2. Get Job Details
**`GET /api/jobs/{id}`**

Fetches current state, worker assignment, and timing metadata for a specific job UUID.

#### Response (`200 OK`)
```json
{
  "id": "7b0a884e-f495-46aa-bd08-8e6f1f4148da",
  "jobType": "EMAIL_SEND",
  "status": "COMPLETED",
  "priority": 3,
  "attemptCount": 1,
  "assignedWorkerId": "worker-pool-3",
  "createdAt": "2026-10-30T17:00:00Z",
  "startedAt": "2026-10-30T17:00:01Z",
  "completedAt": "2026-10-30T17:00:03Z",
  "isDeadLetter": false
}
```

---

### 3. Cancel Job
**`DELETE /api/jobs/{id}`**

Attempts to cancel a job. If the job has not completed, it is transitioned to `CANCELLED` and purged from execution queues.

#### Response (`200 OK`)
```json
{
  "message": "Job successfully cancelled",
  "jobId": "7b0a884e-f495-46aa-bd08-8e6f1f4148da",
  "previousStatus": "QUEUED"
}
```

---

### 4. Re-queue Dead Letter Job
**`POST /api/jobs/dead-letter/{id}/retry`**

Resets failure counters and schedules a dead-lettered job for execution retry.

#### Response (`200 OK`)
```json
{
  "id": "7b0a884e-f495-46aa-bd08-8e6f1f4148da",
  "status": "QUEUED",
  "attemptCount": 0,
  "isDeadLetter": false
}
```

---

### 5. Telemetry & Metrics
**`GET /api/jobs/metrics`**

Provides real-time system metrics for operational dashboards and monitoring scrapers.

#### Response (`200 OK`)
```json
{
  "totalJobs": 12840,
  "pendingCount": 14,
  "runningCount": 6,
  "completedCount": 12795,
  "failedCount": 25,
  "deadLetterCount": 5,
  "activeWorkers": 8,
  "averageProcessingTimeMs": 142.6
}
```
