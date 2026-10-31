# Worker Pool Architecture & Thread Sizing Formulas

This document details the internal design of OptiQueue's worker execution subsystem, including the custom bounded `JobBlockingQueue`, cooperative worker loops, and mathematical formulas for thread pool sizing.

---

## 1. Producer-Consumer Pipeline

The execution architecture follows the classic Producer-Consumer pattern:

```
[ PostgreSQL ] 
      |
      | (Batch Poll every 2s)
      v
+------------------+
| SchedulerService | (Producer)
+------------------+
      |
      | offer(job, 500ms)
      v
+-------------------------------------------------------+
|                 JobBlockingQueue                      |
|  - Storage: ArrayDeque<Job> (Contiguous memory cache) |
|  - Synchronization: ReentrantLock                     |
|  - Conditions: notEmpty, notFull                      |
+-------------------------------------------------------+
      |
      | take()
      v
+------------------+
|   WorkerPool     | (Consumers: N Worker Threads)
+------------------+
      |
      +---> [EmailJobHandler]
      +---> [WebhookJobHandler]
      +---> [ReportGenJobHandler]
      +---> [DefaultJobHandler]
```

---

## 2. Bounded Queue Synchronization Mechanics

OptiQueue implements a custom `JobBlockingQueue` utilizing Java concurrency primitives:

- **`ReentrantLock`**: Protects the critical section during enqueue and dequeue operations.
- **`Condition notEmpty`**: Worker consumer threads await on this condition when the buffer is empty. Signaling occurs whenever `offer()` or `put()` enqueues an element.
- **`Condition notFull`**: Scheduler producer threads await on this condition if the buffer reaches capacity, applying backpressure to DB reads.

### 2.1 Why `ArrayDeque` Over `LinkedList`?
1. **Cache Locality**: Elements in `ArrayDeque` reside in adjacent memory slots in the underlying Object array, minimizing L1/L2/L3 CPU cache misses.
2. **Zero Node Allocation**: `LinkedList` allocates an internal `Node` object on the heap for every enqueued job, creating Garbage Collection overhead.

---

## 3. Mathematical Thread Pool Sizing

Selecting the correct worker pool size is critical to maximizing throughput while avoiding excessive OS context switching:

### Goetz Formula for Thread Pool Sizing:
$$N_{\text{threads}} = N_{\text{CPU}} \times U_{\text{CPU}} \times \left(1 + \frac{W}{C}\right)$$

Where:
- $N_{\text{CPU}}$: Number of available processor cores (e.g. `Runtime.getRuntime().availableProcessors()`)
- $U_{\text{CPU}}$: Target CPU utilization ($0 \le U_{\text{CPU}} \le 1$, typically `0.8` to `0.9`)
- $W$: Average wait / I/O blocking time per job (database queries, network HTTP calls, disk I/O)
- $C$: Average computation time per job (CPU-bound JSON parsing, hashing, formatting)

### Example Calculation:
For an 8-core CPU handling I/O heavy jobs (e.g. Webhook delivery where $W = 180\text{ ms}$ and $C = 20\text{ ms}$):
$$\frac{W}{C} = \frac{180}{20} = 9$$
$$N_{\text{threads}} = 8 \times 0.85 \times (1 + 9) \approx 68\text{ threads}$$

---

## 4. Cooperative Graceful Shutdown

When the application container receives `SIGTERM`:

1. **State Mutation**: The `WorkerPool` sets `AtomicBoolean running` to `false`.
2. **Phase 1 (Stop Ingestion)**: In-flight worker loops finish processing their current claimed job.
3. **Phase 2 (Executor Drain)**: Calls `executor.shutdown()` and waits up to 30 seconds via `executor.awaitTermination(30, TimeUnit.SECONDS)`.
4. **Phase 3 (Forced Termination)**: If workers fail to terminate within the timeout window, `executor.shutdownNow()` interrupts lingering threads.
