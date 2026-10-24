package com.optiqueue.failover;

import com.optiqueue.config.OptiQueueProperties;
import com.optiqueue.domain.Job;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.redis.HeartbeatService;
import com.optiqueue.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailoverDetectorTest — Worker Heartbeat and Orphan Job Recovery")
class FailoverDetectorTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private HeartbeatService heartbeatService;

    private final OptiQueueProperties props = new OptiQueueProperties();

    private FailoverDetector failoverDetector;

    @BeforeEach
    void setUp() {
        failoverDetector = new FailoverDetector(jobRepository, heartbeatService, props);
    }

    @Test
    @DisplayName("Recovers stuck job when worker heartbeat key has expired")
    void recoversStuckJobWhenWorkerDead() {
        String deadWorkerId = "worker-dead-123";
        Job stuckJob = Job.builder()
            .id(UUID.randomUUID())
            .status(JobStatus.IN_PROGRESS)
            .assignedWorkerId(deadWorkerId)
            .startedAt(Instant.now().minusSeconds(60))
            .build();

        when(jobRepository.findStuckInProgressJobs(any(Instant.class)))
            .thenReturn(List.of(stuckJob));
        when(heartbeatService.isWorkerAlive(deadWorkerId))
            .thenReturn(false);

        failoverDetector.detectAndRecoverFailedWorkers();

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());

        Job recovered = captor.getValue();
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(recovered.getAssignedWorkerId()).isNull();
        assertThat(recovered.getStartedAt()).isNull();
        assertThat(recovered.getLastError()).contains("Recovered from dead worker");
    }

    @Test
    @DisplayName("Leaves job alone when worker is still alive (heartbeat active)")
    void leavesJobRunningWhenWorkerAlive() {
        String liveWorkerId = "worker-live-456";
        Job slowJob = Job.builder()
            .id(UUID.randomUUID())
            .status(JobStatus.IN_PROGRESS)
            .assignedWorkerId(liveWorkerId)
            .startedAt(Instant.now().minusSeconds(60))
            .build();

        when(jobRepository.findStuckInProgressJobs(any(Instant.class)))
            .thenReturn(List.of(slowJob));
        when(heartbeatService.isWorkerAlive(liveWorkerId))
            .thenReturn(true);

        failoverDetector.detectAndRecoverFailedWorkers();

        verify(jobRepository, never()).save(any(Job.class));
        assertThat(slowJob.getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("No-op when no stuck jobs found")
    void noOpWhenNoStuckJobs() {
        when(jobRepository.findStuckInProgressJobs(any(Instant.class)))
            .thenReturn(List.of());

        failoverDetector.detectAndRecoverFailedWorkers();

        verifyNoInteractions(heartbeatService);
        verify(jobRepository, never()).save(any(Job.class));
    }
}
