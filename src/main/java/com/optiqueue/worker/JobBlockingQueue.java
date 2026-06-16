package com.optiqueue.worker;

import com.optiqueue.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JobBlockingQueue — a custom bounded, thread-safe blocking queue.
 *
 * Sits between the SchedulerService (producer) and WorkerPool (consumers).
 * Implements the classic producer-consumer pattern using:
 *
 *   - ReentrantLock: mutual exclusion (one thread in the critical section at a time)
 *   - Condition notFull:  producers wait here when the queue is at capacity
 *   - Condition notEmpty: consumers wait here when the queue is empty
 *
 * DESIGN RATIONALE — why not java.util.concurrent.LinkedBlockingQueue?
 *   LBQ is production-ready and we DO use it in real systems. We build our own
 *   here to demonstrate mastery of the underlying primitives. The internal
 *   implementation of LBQ uses this exact same two-lock / two-condition pattern
 *   (actually a two-lock variant — one for head, one for tail). Our single-lock
 *   version is simpler but slightly less concurrent.
 *
 * INTERNAL STORAGE:
 *   ArrayDeque (not LinkedList) for the internal buffer.
 *   WHY ArrayDeque over LinkedList?
 *     - Better cache locality: array elements are contiguous in memory
 *     - No per-node allocation overhead (LinkedList allocates a Node object per element)
 *     - ArrayDeque is ~2x faster than LinkedList for queue operations in benchmarks
 *
 * THREAD SAFETY GUARANTEE:
 *   All public methods acquire the lock before accessing the internal deque.
 *   No method exposes the internal deque reference.
 *   Invariant: 0 <= size() <= capacity, maintained under all concurrent access.
 *
 * INTERVIEW Q: "What's the difference between a counting semaphore and a
 *               Condition variable for a blocking queue?"
 * A: Semaphores count available slots/items. Two semaphores (full, empty)
 *    can implement a blocking queue without an explicit lock, BUT you lose
 *    the ability to atomically check-then-act on the queue contents (e.g.,
 *    you can't atomically drain-and-return-all without separate locking).
 *    The lock+condition approach keeps all invariants under a single lock,
 *    making compound operations (like drainTo) safe to add.
 */
public class JobBlockingQueue {

    private static final Logger log = LoggerFactory.getLogger(JobBlockingQueue.class);

    // ── INTERNAL STATE ────────────────────────────────────────────────────

    /** Circular buffer for jobs. ArrayDeque grows dynamically but we cap it at capacity. */
    private final Deque<Job> queue;

    /**
     * Shutdown flag. When true, all blocking methods stop waiting and return
     * immediately (put returns without adding, take/poll return null).
     * Volatile: written by the shutdown thread, read by all worker threads.
     * The lock's memory barrier also ensures visibility, but volatile makes
     * the intent explicit and allows the flag to be read without holding the lock.
     */
    private volatile boolean shutdown = false;

    /** Maximum number of jobs this queue can hold. Configurable at construction time. */
    private final int capacity;

    // ── CONCURRENCY PRIMITIVES ────────────────────────────────────────────

    /**
     * The single lock guarding all access to `queue`.
     *
     * ReentrantLock(false) = non-fair mode (default).
     * Non-fair: OS chooses which waiting thread runs next. Lower latency,
     * higher throughput, but theoretically a thread could starve.
     *
     * ReentrantLock(true) = fair mode (FIFO ordering of waiting threads).
     * Fair: guaranteed no starvation, but ~3-5x slower due to lock convoy.
     *
     * For a task queue, non-fair is correct: we don't care which worker
     * thread gets the next job, only that jobs are processed quickly.
     */
    private final ReentrantLock lock = new ReentrantLock(false);

    /**
     * Condition for PRODUCER threads (scheduler).
     * Producers call notFull.await() when the queue is at capacity.
     * Consumers call notFull.signal() after removing a job (freeing space).
     *
     * CRITICAL: Only signal notFull from consumer code.
     *           Only await notFull from producer code.
     * Mixing these up causes the bug where producers wake producers.
     */
    private final Condition notFull  = lock.newCondition();

    /**
     * Condition for CONSUMER threads (workers).
     * Consumers call notEmpty.await() when the queue has no jobs.
     * Producers call notEmpty.signal() after adding a job.
     *
     * CRITICAL: Only signal notEmpty from producer code.
     *           Only await notEmpty from consumer code.
     */
    private final Condition notEmpty = lock.newCondition();

    // ── CONSTRUCTOR ───────────────────────────────────────────────────────

    public JobBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0, got: " + capacity);
        }
        this.capacity = capacity;
        this.queue    = new ArrayDeque<>(capacity);
    }

    // ── PRODUCER METHODS ──────────────────────────────────────────────────

    /**
     * Puts a job into the queue, blocking indefinitely if full.
     *
     * Called by SchedulerService to hand jobs off to workers.
     *
     * Step by step:
     *   1. Acquire the lock (blocks until no other thread holds it)
     *   2. Check if full → if yes, release lock and sleep on notFull
     *      (the while loop re-checks when woken — defends against spurious wakeups)
     *   3. Add the job to the tail of the deque
     *   4. Signal ONE consumer that a job is available
     *   5. Release the lock (in finally — always, even if step 3 throws)
     *
     * WHY finally? If queue.add() threw an exception (shouldn't, but defensive),
     * we must still release the lock. A deadlock where a producer crashes holding
     * the lock would freeze the entire system. `try-finally` is non-negotiable.
     *
     * @param job the job to enqueue
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void put(Job job) throws InterruptedException {
        if (job == null) throw new NullPointerException("Cannot enqueue null job");

        lock.lockInterruptibly(); // Acquire lock; throw if thread is interrupted
        try {
            // WHILE not IF — re-check after every wakeup (spurious wakeup defence)
            while (queue.size() >= capacity) {
                if (shutdown) return; // Shutdown in progress — discard and exit
                log.debug("Queue full ({}/{}), producer blocking", queue.size(), capacity);
                notFull.await(); // Atomically: release lock + sleep; re-acquire on wake
            }
            if (shutdown) return; // Recheck after wakeup

            queue.addLast(job);
            log.debug("Job enqueued: {} (queue size: {}/{})", job.getId(), queue.size(), capacity);

            // Wake ONE consumer — exactly one, because only one can claim the job
            // signalAll() would cause a thundering herd: all workers race for one job
            notEmpty.signal();

        } finally {
            lock.unlock(); // ALWAYS release — even if an exception occurs above
        }
    }

    /**
     * Offers a job with a timeout. Returns false if queue is still full after timeout.
     *
     * Used when the scheduler doesn't want to block indefinitely —
     * e.g., during shutdown, or when it wants to try a different action.
     *
     * @param job     the job to enqueue
     * @param timeout max time to wait
     * @param unit    time unit for timeout
     * @return true if the job was enqueued, false if timed out
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(Job job, long timeout, TimeUnit unit) throws InterruptedException {
        if (job == null) throw new NullPointerException("Cannot enqueue null job");

        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() >= capacity) {
                if (shutdown || nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) return false;
            queue.addLast(job);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ── CONSUMER METHODS ──────────────────────────────────────────────────

    /**
     * Takes a job from the queue, blocking indefinitely if empty.
     *
     * Called by each worker thread in an infinite loop.
     *
     * Mirror image of put():
     *   1. Acquire lock
     *   2. While empty → sleep on notEmpty
     *   3. Remove job from head of deque
     *   4. Signal ONE producer that space is available
     *   5. Release lock in finally
     *
     * INTERVIEW Q: "Why does take() throw InterruptedException?"
     * A: Threads blocking in await() can be interrupted externally via
     *    thread.interrupt(). This is the standard Java mechanism for
     *    cooperative shutdown: when we call workerPool.shutdown(), it
     *    interrupts all blocked worker threads, causing take() to throw,
     *    which the worker catches and exits its loop cleanly.
     *    Without InterruptedException, we'd have no way to stop workers
     *    blocked waiting for jobs during shutdown.
     *
     * @return the next job to process (FIFO order — jobs added by priority-sorted
     *         scheduler, so deque head is always the highest-priority job)
     * @throws InterruptedException if the thread is interrupted (shutdown signal)
     */
    public Job take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (shutdown) return null; // Shutdown — no more jobs coming
                log.debug("Queue empty, worker blocking on notEmpty");
                notEmpty.await(); // Release lock + sleep; re-acquire on wake
            }

            Job job = queue.removeFirst();
            log.debug("Job dequeued: {} (queue size: {}/{})", job.getId(), queue.size(), capacity);

            // Signal ONE producer that a slot opened up
            notFull.signal();

            return job;

        } finally {
            lock.unlock();
        }
    }

    /**
     * Polls for a job with a timeout. Returns null if no job arrives within timeout.
     *
     * Used during graceful shutdown: workers use poll() with a short timeout
     * so they periodically check a shutdown flag, rather than blocking forever
     * in take() and never seeing the shutdown signal.
     *
     * PRODUCTION PATTERN: Worker loop looks like:
     *   while (!shutdown) {
     *       Job job = queue.poll(1, SECONDS);
     *       if (job != null) process(job);
     *       // Loop back, re-check shutdown flag
     *   }
     *
     * @return the next job, or null if timeout elapsed with no job available
     */
    public Job poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (shutdown || nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            Job job = queue.removeFirst();
            notFull.signal();
            return job;
        } finally {
            lock.unlock();
        }
    }

    // ── OBSERVATION METHODS ───────────────────────────────────────────────

    /**
     * Returns the current number of jobs in the queue.
     * Acquires the lock for a consistent read.
     *
     * WHY lock for a read? On 64-bit JVM, int reads are atomic, but
     * `queue.size()` delegates to ArrayDeque's internal counter — without
     * the lock, we might read a stale value from another CPU's cache.
     * The lock forces a memory barrier, ensuring visibility.
     *
     * ALTERNATIVE: volatile int size — but then we'd need to manage it manually.
     * The lock is simpler and already required for all writes anyway.
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity; // Immutable — no lock needed
    }

    /**
     * Wakes all waiting threads so they can check a shutdown flag and exit.
     *
     * Called during application shutdown (Step 5). Without this, workers blocked
     * in take() would hang indefinitely even after shutdown is requested, because
     * no one signals notEmpty once the scheduler has stopped producing.
     *
     * This does NOT remove any jobs — threads wake, re-check their shutdown flag,
     * and exit if set. Jobs still in the queue at shutdown are lost (acceptable
     * since they'll be recovered from DB on next startup via PENDING status).
     */
    public void signalAllForShutdown() {
        lock.lock();
        try {
            shutdown = true;          // Set flag FIRST — threads read this after waking
            notEmpty.signalAll();     // Wake all waiting consumers
            notFull.signalAll();      // Wake all waiting producers
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("JobBlockingQueue[size=%d, capacity=%d]",
                queue.size(), capacity);
        } finally {
            lock.unlock();
        }
    }
}
