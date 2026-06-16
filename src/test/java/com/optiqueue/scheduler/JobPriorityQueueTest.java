package com.optiqueue.scheduler;

import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * JobPriorityQueueTest — unit tests for the custom min-heap.
 *
 * These are pure unit tests (no Spring, no DB, no Docker).
 * They run in ~50ms total. This is how you test data structure logic:
 * isolate it completely, verify invariants.
 *
 * We use AssertJ (bundled with spring-boot-starter-test) for fluent assertions.
 * AssertJ reads like English: assertThat(actual).isEqualTo(expected)
 *
 * INTERVIEW Q: "How do you test a concurrent data structure?"
 * A: First test the algorithm correctness with single-threaded tests (this file).
 *    Then stress-test with multiple threads submitting/polling simultaneously
 *    and verify no invariants are violated (Step 11).
 */
@DisplayName("JobPriorityQueue — Min-Heap Unit Tests")
class JobPriorityQueueTest {

    private JobPriorityQueue queue;

    @BeforeEach
    void setUp() {
        queue = new JobPriorityQueue(4); // Start small to test resize
    }

    // ── BASIC OPERATIONS ──────────────────────────────────────────────────

    @Test
    @DisplayName("Empty queue: isEmpty=true, size=0, peek=null, poll throws")
    void emptyQueue() {
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
        assertThat(queue.peek()).isNull();
        assertThatThrownBy(() -> queue.poll())
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("Single insert: size=1, peek returns it, poll returns it and queue is empty")
    void singleInsertAndPoll() {
        Job job = buildJob(1, null);
        queue.insert(job);

        assertThat(queue.isEmpty()).isFalse();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.peek()).isSameAs(job);

        Job polled = queue.poll();
        assertThat(polled).isSameAs(job);
        assertThat(queue.isEmpty()).isTrue();
    }

    // ── PRIORITY ORDERING ─────────────────────────────────────────────────

    @Test
    @DisplayName("Poll order: lower priority integer = polled first")
    void priorityOrdering() {
        Job low    = buildJob(10, null);   // low urgency
        Job high   = buildJob(1,  null);   // high urgency
        Job medium = buildJob(5,  null);   // medium urgency

        // Insert in non-sorted order
        queue.insert(low);
        queue.insert(high);
        queue.insert(medium);

        // Should come out: high(1) → medium(5) → low(10)
        assertThat(queue.poll().getPriority()).isEqualTo(1);
        assertThat(queue.poll().getPriority()).isEqualTo(5);
        assertThat(queue.poll().getPriority()).isEqualTo(10);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Heap property: peek always returns minimum even after multiple inserts")
    void peekAlwaysReturnsMin() {
        // Insert 10 jobs in reverse priority order
        for (int p = 10; p >= 1; p--) {
            queue.insert(buildJob(p, null));
            // After every insert, peek should always return priority=1
            // once it's been inserted
        }
        assertThat(queue.peek().getPriority()).isEqualTo(1);
    }

    // ── DEADLINE TIE-BREAKING ─────────────────────────────────────────────

    @Test
    @DisplayName("Same priority: job with earlier deadline polled first (EDF)")
    void deadlineTieBreaking() {
        Instant now = Instant.now();

        Job laterDeadline   = buildJob(5, now.plusSeconds(3600));  // 1 hour from now
        Job earlierDeadline = buildJob(5, now.plusSeconds(60));    // 1 minute from now
        Job noDeadline      = buildJob(5, null);                   // No constraint

        queue.insert(laterDeadline);
        queue.insert(noDeadline);
        queue.insert(earlierDeadline);

        // EDF within same priority: earliest deadline first, null = last
        assertThat(queue.poll()).isSameAs(earlierDeadline);
        assertThat(queue.poll()).isSameAs(laterDeadline);
        assertThat(queue.poll()).isSameAs(noDeadline);
    }

    // ── FIFO TIE-BREAKING ─────────────────────────────────────────────────

    @Test
    @DisplayName("Same priority and no deadline: FIFO order preserved")
    void fifoTieBreaking() throws InterruptedException {
        // Build jobs with same priority and no deadline but different createdAt
        Job first  = buildJobWithCreatedAt(5, null, Instant.now());
        Thread.sleep(10); // Ensure distinct Instant values
        Job second = buildJobWithCreatedAt(5, null, Instant.now());
        Thread.sleep(10);
        Job third  = buildJobWithCreatedAt(5, null, Instant.now());

        // Insert out of order
        queue.insert(third);
        queue.insert(first);
        queue.insert(second);

        // Should come out FIFO
        assertThat(queue.poll()).isSameAs(first);
        assertThat(queue.poll()).isSameAs(second);
        assertThat(queue.poll()).isSameAs(third);
    }

    // ── DYNAMIC RESIZING ──────────────────────────────────────────────────

    @Test
    @DisplayName("Resizing: can insert more elements than initial capacity")
    void dynamicResizing() {
        // Initial capacity = 4, insert 20 elements
        for (int i = 20; i >= 1; i--) {
            queue.insert(buildJob(i, null));
        }

        assertThat(queue.size()).isEqualTo(20);

        // Should still come out in order
        for (int i = 1; i <= 20; i++) {
            assertThat(queue.poll().getPriority()).isEqualTo(i);
        }
        assertThat(queue.isEmpty()).isTrue();
    }

    // ── REMOVE (CANCELLATION) ─────────────────────────────────────────────

    @Test
    @DisplayName("Remove: removing a job from middle of heap maintains heap property")
    void removeFromMiddle() {
        Job a = buildJob(1, null);
        Job b = buildJob(3, null);
        Job c = buildJob(5, null);
        Job d = buildJob(7, null);

        queue.insert(a);
        queue.insert(b);
        queue.insert(c);
        queue.insert(d);

        // Remove 'b' from the middle
        boolean removed = queue.remove(b);

        assertThat(removed).isTrue();
        assertThat(queue.size()).isEqualTo(3);

        // Remaining jobs should still come out in priority order
        assertThat(queue.poll()).isSameAs(a);
        assertThat(queue.poll()).isSameAs(c);
        assertThat(queue.poll()).isSameAs(d);
    }

    @Test
    @DisplayName("Remove: removing non-existent job returns false")
    void removeNonExistent() {
        queue.insert(buildJob(1, null));
        Job ghost = buildJob(99, null);

        assertThat(queue.remove(ghost)).isFalse();
        assertThat(queue.size()).isEqualTo(1);
    }

    // ── HEAP INVARIANT VERIFICATION ───────────────────────────────────────

    @Test
    @DisplayName("Heap invariant: every parent has lower priority value than its children")
    void heapInvariantHolds() {
        // Insert 50 random priority jobs
        for (int i = 0; i < 50; i++) {
            queue.insert(buildJob((int)(Math.random() * 10) + 1, null));
        }

        // Verify heap property on the internal array
        Job[] arr = queue.toArray();
        for (int i = 0; i < arr.length; i++) {
            int left  = 2 * i + 1;
            int right = 2 * i + 2;

            if (left < arr.length) {
                assertThat(arr[i].getPriority())
                    .as("Parent at %d should have priority <= left child at %d", i, left)
                    .isLessThanOrEqualTo(arr[left].getPriority());
            }
            if (right < arr.length) {
                assertThat(arr[i].getPriority())
                    .as("Parent at %d should have priority <= right child at %d", i, right)
                    .isLessThanOrEqualTo(arr[right].getPriority());
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private Job buildJob(int priority, Instant deadline) {
        return buildJobWithCreatedAt(priority, deadline, Instant.now());
    }

    private Job buildJobWithCreatedAt(int priority, Instant deadline, Instant createdAt) {
        return Job.builder()
            .id(UUID.randomUUID())
            .jobType("TEST")
            .priority(priority)
            .deadline(deadline)
            .status(JobStatus.PENDING)
            .createdAt(createdAt)
            .retryCount(0)
            .maxRetries(5)
            .isDeadLetter(false)
            .build();
    }
}
