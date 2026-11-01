# Pluggable Job Handler Architecture

This guide describes how OptiQueue executes diverse workload types using the **Strategy Pattern** and the `JobHandlerRegistry`.

---

## 1. Overview & Strategy Pattern

In OptiQueue, background tasks are polymorphic. Rather than embedding conditional `if/else` or `switch` statements across worker threads, task execution is abstracted behind the `JobHandler` interface:

```
                      +--------------------+
                      |     WorkerPool     |
                      +--------------------+
                                 |
                     job.getJobType() -> "EMAIL_SEND"
                                 v
                      +--------------------+
                      | JobHandlerRegistry |
                      +--------------------+
                                 |
        +------------------------+------------------------+
        |                        |                        |
        v                        v                        v
+------------------+   +--------------------+   +-------------------+
| EmailJobHandler  |   | WebhookJobHandler  |   | ReportGenHandler  |
|  ("EMAIL_SEND")  |   |("WEBHOOK_DISPATCH")|   |   ("REPORT_GEN")  |
+------------------+   +--------------------+   +-------------------+
```

---

## 2. Built-in Handlers

| Handler | Job Type Key | Description |
|---|---|---|
| **`EmailJobHandler`** | `EMAIL_SEND` | Simulates asynchronous transactional email dispatch (SMTP/AWS SES) |
| **`WebhookJobHandler`** | `WEBHOOK_DISPATCH` | Handles outbound HTTP POST webhook notifications with response code evaluation |
| **`ReportGenJobHandler`** | `REPORT_GEN` | Simulates CPU/memory-intensive report compilation and export |
| **`DefaultJobHandler`** | `*` (Catch-all) | Safe fallback handler for unmapped job types |

---

## 3. Implementing a Custom Job Handler

Adding a new workload type to OptiQueue requires zero modifications to the core scheduling or worker engines.

### Step 1: Implement `JobHandler`
```java
package com.optiqueue.worker.handler;

import com.optiqueue.domain.Job;
import org.springframework.stereotype.Component;

@Component
public class ImageProcessingJobHandler implements JobHandler {

    @Override
    public String getJobType() {
        return "IMAGE_RESIZE";
    }

    @Override
    public String execute(Job job) throws Exception {
        // Parse payload
        String payload = job.getPayload();
        
        // Execute domain processing
        // (e.g. download source image, apply transformations, upload thumbnail)
        
        // Return JSON telemetry or execution metadata
        return "{\"status\":\"SUCCESS\",\"dimensions\":\"800x600\",\"bytesProcessed\":42000}";
    }
}
```

### Step 2: Automatic Discovery
Spring Boot automatically detects the `@Component` and injects it into `JobHandlerRegistry(List<JobHandler> handlerList)`. The registry registers the type key (case-insensitively) during application startup.

### Step 3: Failure & Retries
- If `execute(Job job)` throws an unhandled exception, `WorkerPool` catches it, records the error string in `job.lastError`, and invokes `RetryEngine` to schedule exponential backoff.
- If execution returns cleanly, the returned string is persisted to `jobs.result` and the status transitions to `COMPLETED`.
