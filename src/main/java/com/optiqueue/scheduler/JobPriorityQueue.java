package com.optiqueue.scheduler;

import com.optiqueue.domain.Job;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JobPriorityQueue — a custom min-heap priority queue for scheduling jobs.
 *
 * Built from scratch (not java.util.PriorityQueue) to demonstrate:
 *   - Array-based binary heap structure
 *   - Sift-up (for insert) and sift-down (for poll) operations
 *   - O(log n) insert and poll, O(1) peek
 *   - Custom multi-field comparator (priority → deadline → FIFO)
 *
 * THREAD SAFETY:
 *   This class is NOT thread-safe by itself. It is used exclusively
 *   by the SchedulerService, which holds a ReentrantLock around all
 *   access to this queue (see SchedulerService in Step 5).
 *
 *   WHY not synchronize here? The Lock lives at the service layer because
 *   the service often needs to do multiple operations atomically
 *   (e.g., peek + conditionally poll + update state). If we locked inside
 *   each method, a race condition could occur between peek() and poll().
 *   This is called "external locking" — the caller controls the lock scope.
 *
 * HEAP PROPERTY (min-heap):
 *   parent <= left child  AND  parent <= right child
 *   The root (index 0) always holds the highest-priority job.
 *
 * INDEX ARITHMETIC:
 *   parent(i)     = (i - 1) / 2
 *   leftChild(i)  = 2 * i + 1
 *   rightChild(i) = 2 * i + 2
 *
 * INTERVIEW Q: "Why store a heap in an array instead of linked nodes?"
 * A: Arrays have better cache locality — elements are contiguous in memory.
 *    A linked tree requires pointer chasing which causes cache misses.
 *    Arrays also have zero per-node overhead (no left/right pointers).
 *    The index arithmetic is constant-time, so there's no algorithmic cost.
 */
public class JobPriorityQueue {

    // ── INTERNAL STORAGE ──────────────────────────────────────────────────

    /**
     * The heap array. We grow it dynamically when full (like ArrayList).
     * Initial capacity 16 — a reasonable default for a work queue.
     */
    private Job[] heap;

    /**
     * Current number of elements in the heap.
     * heap[0..size-1] are valid; heap[size..heap.length-1] are garbage.
     */
    private int size;

    // ── CONSTRUCTOR ───────────────────────────────────────────────────────

    public JobPriorityQueue(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be > 0, got: " + initialCapacity);
        }
        this.heap = new Job[initialCapacity];
        this.size = 0;
    }

    public JobPriorityQueue() {
        this(16);
    }

    // ── PUBLIC API ────────────────────────────────────────────────────────

    /**
     * Inserts a job into the priority queue.
     *
     * Algorithm:
     *   1. Ensure capacity (grow array if needed)
     *   2. Place job at the next available leaf position (index = size)
     *   3. Increment size
     *   4. Sift up: compare with parent, swap if smaller, repeat
     *
     * Time complexity: O(log n)
     * Space complexity: O(1) amortized (O(n) on rare resize events)
     *
     * @param job the job to enqueue
     */
    public void insert(Job job) {
        if (job == null) {
            throw new IllegalArgumentException("Cannot insert null job");
        }

        ensureCapacity();

        heap[size] = job;       // Place at the end (next leaf position)
        siftUp(size);           // Restore heap property by bubbling up
        size++;
    }

    /**
     * Removes and returns the highest-priority job (root of the min-heap).
     *
     * Algorithm:
     *   1. Save root (index 0) — this is the minimum (highest priority)
     *   2. Move the last element to the root position
     *   3. Null out the old last position and decrement size
     *   4. Sift down: compare root with smaller child, swap if larger, repeat
     *
     * Time complexity: O(log n)
     *
     * @return the highest-priority job
     * @throws NoSuchElementException if the queue is empty
     */
    public Job poll() {
        if (isEmpty()) {
            throw new NoSuchElementException("Priority queue is empty");
        }

        Job min = heap[0];          // Save the minimum (highest priority)
        size--;
        heap[0] = heap[size];       // Move last element to root
        heap[size] = null;          // Null out last slot (prevent memory leak)
        siftDown(0);                // Restore heap property by bubbling down

        return min;
    }

    /**
     * Returns but does NOT remove the highest-priority job.
     *
     * Time complexity: O(1) — root is always at index 0.
     *
     * @return the highest-priority job, or null if empty
     */
    public Job peek() {
        return isEmpty() ? null : heap[0];
    }

    /**
     * @return true if the queue contains no jobs
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * @return the number of jobs currently in the queue
     */
    public int size() {
        return size;
    }

    /**
     * Removes a specific job from anywhere in the heap.
     *
     * Used when a job is cancelled via the REST API — it may be sitting
     * anywhere in the heap, not just at the root.
     *
     * Algorithm:
     *   1. Find the job by linear scan — O(n)
     *   2. Swap with last element
     *   3. Shrink size
     *   4. Sift up OR sift down to restore heap property
     *      (the replacement could be smaller or larger than neighbors)
     *
     * Time complexity: O(n) for find + O(log n) for restore = O(n)
     * This is acceptable because cancellation is rare compared to insert/poll.
     *
     * PRODUCTION NOTE: A Fibonacci heap can do decrease-key in O(1) amortized,
     * making deletion O(log n). For this project O(n) scan is fine.
     *
     * @param job the job to remove
     * @return true if the job was found and removed
     */
    public boolean remove(Job job) {
        if (job == null) return false;

        // Linear scan to find the job — O(n)
        int index = -1;
        for (int i = 0; i < size; i++) {
            if (heap[i].getId().equals(job.getId())) {
                index = i;
                break;
            }
        }

        if (index == -1) return false; // Not in this queue

        size--;
        heap[index] = heap[size];   // Replace with last element
        heap[size] = null;           // Clear last slot

        if (index < size) {
            // Try sift up first — if the replacement is smaller than its parent
            siftUp(index);
            // Then sift down — if the replacement is larger than its children
            // Note: only one of these will actually do work
            siftDown(index);
        }

        return true;
    }

    // ── HEAP OPERATIONS ───────────────────────────────────────────────────

    /**
     * Sift UP: move element at `index` toward the root until heap property holds.
     *
     * Called after insert (new element is at the bottom, may be too small).
     *
     * Loop invariant: heap[parent(i)] <= heap[i] for all i outside [index..root]
     *
     * @param index starting position to sift up from
     */
    private void siftUp(int index) {
        while (index > 0) {
            int parentIndex = (index - 1) / 2;

            // If current element has higher priority (smaller comparator value)
            // than its parent, swap them.
            if (compare(heap[index], heap[parentIndex]) < 0) {
                swap(index, parentIndex);
                index = parentIndex;  // Continue sifting from the parent's position
            } else {
                break;  // Heap property satisfied — done
            }
        }
    }

    /**
     * Sift DOWN: move element at `index` toward the leaves until heap property holds.
     *
     * Called after poll (root is replaced with last element, which may be too large).
     *
     * Algorithm:
     *   At each step, find the SMALLER of the two children.
     *   If the current node is larger than the smaller child, swap.
     *   Repeat until no children or no swap needed.
     *
     * @param index starting position to sift down from
     */
    private void siftDown(int index) {
        while (true) {
            int leftChild  = 2 * index + 1;
            int rightChild = 2 * index + 2;
            int smallest   = index;   // Assume current is smallest

            // Is left child higher priority than current?
            if (leftChild < size && compare(heap[leftChild], heap[smallest]) < 0) {
                smallest = leftChild;
            }

            // Is right child higher priority than current smallest?
            if (rightChild < size && compare(heap[rightChild], heap[smallest]) < 0) {
                smallest = rightChild;
            }

            if (smallest == index) {
                break;  // Current node is already smaller than both children — done
            }

            swap(index, smallest);
            index = smallest;     // Continue sifting from the child's position
        }
    }

    // ── COMPARATOR ────────────────────────────────────────────────────────

    /**
     * Three-level comparator defining "higher priority" order.
     *
     * Returns negative if `a` should be processed BEFORE `b`.
     *
     * Level 1 — Priority value (lower int = more urgent):
     *   job with priority=1 beats job with priority=5
     *
     * Level 2 — Deadline (earlier = more urgent; null = no deadline = lowest urgency):
     *   job with deadline 10:00 beats job with deadline 11:00
     *   job with no deadline (null) goes last within same priority band
     *   This is Earliest Deadline First (EDF) within a priority tier.
     *
     * Level 3 — Created at (earlier = submitted first = FIFO tie-break):
     *   Among identical priority + deadline, preserve submission order.
     *
     * INTERVIEW Q: "What scheduling algorithm does this implement?"
     * A: A hybrid of fixed-priority scheduling (Level 1) and EDF (Level 2).
     *    Pure EDF is theoretically optimal for meeting deadlines on a single
     *    processor, but it can starve low-priority jobs. Our priority-first
     *    approach ensures critical jobs always run first, then EDF within
     *    each priority band prevents deadline misses among same-priority jobs.
     */
    private int compare(Job a, Job b) {
        // Level 1: Priority (lower int = higher urgency)
        int cmp = Integer.compare(a.getPriority(), b.getPriority());
        if (cmp != 0) return cmp;

        // Level 2: Deadline (EDF within same priority band)
        if (a.getDeadline() == null && b.getDeadline() == null) {
            // Both have no deadline — fall through to Level 3
        } else if (a.getDeadline() == null) {
            return 1;   // a has no deadline → b goes first
        } else if (b.getDeadline() == null) {
            return -1;  // b has no deadline → a goes first
        } else {
            cmp = a.getDeadline().compareTo(b.getDeadline());
            if (cmp != 0) return cmp;
        }

        // Level 3: FIFO by submission time
        if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
        return a.getCreatedAt().compareTo(b.getCreatedAt());
    }

    // ── INTERNAL UTILITIES ────────────────────────────────────────────────

    /**
     * Swaps elements at positions i and j in the heap array.
     * Java arrays hold references, so this is just a pointer swap — O(1).
     */
    private void swap(int i, int j) {
        Job temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }

    /**
     * Grows the heap array when it reaches capacity.
     *
     * Growth strategy: double the capacity.
     * WHY double? Doubling gives O(1) amortized cost per insert.
     * If we grew by 1 each time, every insert would cost O(n) for the copy.
     * If we grew by 2x, the total copy work across N inserts is:
     *   1 + 2 + 4 + ... + N = 2N → O(1) amortized per insert.
     *
     * This is the same strategy used by ArrayList, StringBuilder, and
     * std::vector in C++.
     */
    private void ensureCapacity() {
        if (size == heap.length) {
            int newCapacity = heap.length * 2;
            heap = Arrays.copyOf(heap, newCapacity);
        }
    }

    /**
     * Returns a snapshot of current queue contents for debugging/monitoring.
     * Does NOT return them in priority order — just heap array order.
     * To drain in order, repeatedly call poll() on a copy.
     */
    public Job[] toArray() {
        return Arrays.copyOf(heap, size);
    }

    @Override
    public String toString() {
        if (isEmpty()) return "JobPriorityQueue[]";
        StringBuilder sb = new StringBuilder("JobPriorityQueue[size=")
            .append(size).append(", head=");
        if (heap[0] != null) {
            sb.append("Job{id=").append(heap[0].getId())
              .append(", priority=").append(heap[0].getPriority())
              .append("}");
        }
        return sb.append("]").toString();
    }
}
