package com.optiqueue.service;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.controller.JobRequest;
import com.optiqueue.controller.JobResponse;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.repository.JobRepository;
import com.optiqueue.service.JobService.JobSubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IdempotencyTest — unit tests for duplicate job submission handling.
 *
 * WHY Mockito (not Testcontainers / @SpringBootTest)?
 *   This is pure business logic — the idempotency contract lives in JobService.
 *   We mock the repository to control exactly what it returns:
 *     - "existing job found" scenario → test the return-existing path
 *     - "no job found" scenario → test the create-new path
 *   No real DB needed. Tests run in ~50ms.
 *
 * @ExtendWith(MockitoExtension.class): activates Mockito annotations.
 *   @Mock creates a mock instance. @InjectMocks creates the real JobService
 *   and injects mocks into its constructor fields.
 *
 * INTERVIEW Q: "When do you use Mockito vs Testcontainers?"
 * A: Mockito = when testing business logic in isolation (fast, no infrastructure).
 *    Testcontainers = when testing DB queries, schema, migrations, or true
 *    integration (slow, needs Docker, tests the whole stack).
 *    Rule: mock at the boundary of the class under test. Don't mock things
 *    you don't own (e.g., don't mock Java standard library classes).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyTest — Duplicate Job Submission Handling")
class IdempotencyTest {

    @Mock
    private JobRepository jobRepository;

    private final OptiQueueProperties props = new OptiQueueProperties();

    private JobService jobService;

    private static final String IDEMPOTENCY_KEY = "order-123-welcome-email";

    @BeforeEach
    void setUp() {
        jobService = new JobService(jobRepository, props);
    }

    // ── IDEMPOTENT RETURN ──────────────────────────────────────────────────

    @Test
    @DisplayName("Re-submission with same key returns existing job, isNew=false")
    void duplicateSubmissionReturnsExistingJob() {
        // GIVEN: a job with this idempotency key already exists in DB
        Job existingJob = buildExistingJob(IDEMPOTENCY_KEY);
        when(jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(existingJob));

        // WHEN: client submits again with the same key
        JobRequest request = buildRequest(IDEMPOTENCY_KEY);
        JobSubmitResult result = jobService.submitJob(request);

        // THEN: the existing job is returned, isNew=false
        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getId()).isEqualTo(existingJob.getId());
        assertThat(result.response().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

        // AND: no new job was inserted
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Re-submission returns the job in its CURRENT state, not original")
    void duplicateSubmissionReturnsCurrentState() {
        // GIVEN: job was submitted and has since been COMPLETED
        Job completedJob = buildExistingJob(IDEMPOTENCY_KEY);
        completedJob.setStatus(JobStatus.COMPLETED);
        completedJob.setResult("{\"sent\": true}");
        completedJob.setCompletedAt(Instant.now());

        when(jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(completedJob));

        // WHEN: client re-submits (e.g., didn't cache the result)
        JobSubmitResult result = jobService.submitJob(buildRequest(IDEMPOTENCY_KEY));

        // THEN: the current (COMPLETED) state is returned
        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(result.response().getResult()).isEqualTo("{\"sent\": true}");
    }

    @Test
    @DisplayName("Re-submission returns the job even if it's FAILED or DEAD_LETTER")
    void duplicateSubmissionWorksForAllStates() {
        // GIVEN: job is in dead-letter queue
        Job dlqJob = buildExistingJob(IDEMPOTENCY_KEY);
        dlqJob.setStatus(JobStatus.DEAD_LETTER);
        dlqJob.setDeadLetter(true);
        dlqJob.setRetryCount(5);
        dlqJob.setLastError("Permanent failure");

        when(jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(dlqJob));

        JobSubmitResult result = jobService.submitJob(buildRequest(IDEMPOTENCY_KEY));

        // The DLQ job is returned — client knows their job is dead and needs manual action
        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(result.response().isDeadLetter()).isTrue();
    }

    // ── NEW JOB CREATION ──────────────────────────────────────────────────

    @Test
    @DisplayName("First submission with a new key creates a new job, isNew=true")
    void firstSubmissionCreatesNewJob() {
        // GIVEN: no existing job with this key
        when(jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        // Simulate save() returning the saved entity
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job saved = invocation.getArgument(0);
            // Simulate @PrePersist
            if (saved.getCreatedAt() == null) saved.setCreatedAt(Instant.now());
            if (saved.getUpdatedAt() == null) saved.setUpdatedAt(Instant.now());
            return saved;
        });

        // WHEN: client submits for the first time
        JobRequest request = buildRequest(IDEMPOTENCY_KEY);
        request.setPriority(3);
        JobSubmitResult result = jobService.submitJob(request);

        // THEN: a new job is created
        assertThat(result.isNew()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(result.response().getPriority()).isEqualTo(3);
        assertThat(result.response().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

        // AND: save() was called exactly once
        verify(jobRepository, times(1)).save(any(Job.class));
    }

    @Test
    @DisplayName("Submission without idempotency key still creates a job (key defaults to UUID)")
    void submissionWithoutKeyCreatesJob() {
        // GIVEN: no idempotency key provided
        JobRequest request = new JobRequest();
        request.setJobType("TEST_JOB");
        request.setPriority(5);
        // idempotencyKey is null

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setCreatedAt(Instant.now());
            j.setUpdatedAt(Instant.now());
            return j;
        });

        // No findByIdempotencyKey call expected — key is null
        JobSubmitResult result = jobService.submitJob(request);

        assertThat(result.isNew()).isTrue();
        // The idempotency key defaults to the job's UUID (set in submitJob)
        assertThat(result.response().getIdempotencyKey()).isNotNull();

        // Verify findByIdempotencyKey was NOT called (null/blank key skips check)
        verify(jobRepository, never()).findByIdempotencyKey(any());
    }

    // ── IDEMPOTENCY KEY LOOKUP ────────────────────────────────────────────

    @Test
    @DisplayName("findByIdempotencyKey returns job response when key exists")
    void lookupByKeyReturnsExistingJob() {
        Job job = buildExistingJob(IDEMPOTENCY_KEY);
        when(jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(job));

        Optional<JobResponse> result = jobService.findByIdempotencyKey(IDEMPOTENCY_KEY);

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("findByIdempotencyKey returns empty when key does not exist")
    void lookupByKeyReturnsEmptyForUnknownKey() {
        when(jobRepository.findByIdempotencyKey("unknown-key"))
            .thenReturn(Optional.empty());

        Optional<JobResponse> result = jobService.findByIdempotencyKey("unknown-key");

        assertThat(result).isEmpty();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private Job buildExistingJob(String idempotencyKey) {
        return Job.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(idempotencyKey)
            .jobType("EMAIL_SEND")
            .payload("{\"to\":\"user@example.com\"}")
            .status(JobStatus.PENDING)
            .priority(5)
            .retryCount(0)
            .maxRetries(5)
            .isDeadLetter(false)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }

    private JobRequest buildRequest(String idempotencyKey) {
        JobRequest r = new JobRequest();
        r.setIdempotencyKey(idempotencyKey);
        r.setJobType("EMAIL_SEND");
        r.setPayload("{\"to\":\"user@example.com\"}");
        r.setPriority(5);
        return r;
    }
}
