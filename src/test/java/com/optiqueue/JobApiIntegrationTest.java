package com.optiqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiqueue.controller.JobRequest;
import com.optiqueue.domain.JobStatus;
import com.optiqueue.repository.JobRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JobApiIntegrationTest — full-stack integration tests.
 *
 * Uses REAL Postgres + REAL Redis via Testcontainers.
 * Spring Boot test context + Flyway migrations run on actual containers.
 *
 * INTERVIEW Q: "What does @SpringBootTest do?"
 * A: Loads the FULL application context (all beans, configs, DB connections).
 *    Equivalent to starting the real app — but in-process for testing.
 *    Contrast with @WebMvcTest (controller layer only, mocked services)
 *    or @DataJpaTest (JPA layer only, in-memory DB).
 *    Use @SpringBootTest when you need to test cross-layer integration.
 *
 * INTERVIEW Q: "Why Testcontainers over H2 in-memory DB?"
 * A: H2 is not Postgres. It has different:
 *    - SQL dialects (H2 doesn't support all Postgres-specific syntax)
 *    - Constraint behaviour (partial indexes, UUID types)
 *    - Migration compatibility (Flyway scripts written for Postgres may fail on H2)
 *    Testcontainers spins up the real Postgres binary — what you test is what runs in prod.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
class JobApiIntegrationTest {

    // ── CONTAINERS ────────────────────────────────────────────────────────

    /**
     * @Container: Testcontainers manages lifecycle (start before tests, stop after).
     * static: shared across all test methods in this class (faster — one DB per class).
     * reuse=true: keeps container alive between test runs if unchanged (dev speed).
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgres:15-alpine"))
        .withDatabaseName("optiqueue_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    /**
     * @DynamicPropertySource: overrides Spring properties at test runtime.
     * Testcontainers assigns random host ports — we tell Spring to use them.
     * Without this, the app would try to connect to localhost:5432 (not our container).
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Delay background scheduling scans during REST API integration tests to prevent race conditions
        registry.add("scheduler.poll-interval-ms", () -> "3600000");
        registry.add("retry.scan-interval-ms", () -> "3600000");
        registry.add("retry.dlq-scan-interval-ms", () -> "3600000");
        registry.add("failover.scan-interval-ms", () -> "3600000");
    }

    // ── AUTOWIRED BEANS ───────────────────────────────────────────────────

    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired JobRepository   jobRepository;

    // ── TEST DATA ─────────────────────────────────────────────────────────

    private static UUID submittedJobId;
    private static final String IDEMPOTENCY_KEY = "integration-test-" + UUID.randomUUID();

    // ── TESTS ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/jobs → 201 Created, job in DB")
    void submitJob_returns201() throws Exception {
        JobRequest request = new JobRequest();
        request.setJobType("INTEGRATION_TEST");
        request.setPayload("{\"test\": true}");
        request.setPriority(3);
        request.setIdempotencyKey(IDEMPOTENCY_KEY);

        String responseJson = mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.jobType").value("INTEGRATION_TEST"))
            .andExpect(jsonPath("$.priority").value(3))
            .andExpect(jsonPath("$.idempotencyKey").value(IDEMPOTENCY_KEY))
            .andReturn()
            .getResponse()
            .getContentAsString();

        submittedJobId = UUID.fromString(
            objectMapper.readTree(responseJson).get("id").asText());

        // Verify job actually persisted in DB
        Assertions.assertTrue(jobRepository.existsById(submittedJobId));
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/jobs (same key) → 200 OK (idempotent)")
    void submitJob_duplicate_returns200() throws Exception {
        JobRequest request = new JobRequest();
        request.setJobType("INTEGRATION_TEST");
        request.setPriority(3);
        request.setIdempotencyKey(IDEMPOTENCY_KEY); // Same key as test 1

        mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk()) // 200, not 201
            .andExpect(jsonPath("$.id").value(submittedJobId.toString()));

        // Still only ONE job in DB
        Assertions.assertEquals(1,
            jobRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)
                .stream().count());
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/jobs/{id} → 200 OK with correct status")
    void getJob_returnsJob() throws Exception {
        mockMvc.perform(get("/api/jobs/" + submittedJobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(submittedJobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/jobs/{id} (unknown) → 404")
    void getJob_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    @Order(5)
    @DisplayName("GET /api/jobs/{id}/result → 204 (job not yet completed)")
    void getResult_notCompleted_returns204() throws Exception {
        mockMvc.perform(get("/api/jobs/" + submittedJobId + "/result"))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/jobs (invalid) → 400 with fieldErrors")
    void submitJob_invalid_returns400() throws Exception {
        JobRequest invalid = new JobRequest();
        // jobType is @NotBlank — leaving it empty should fail
        invalid.setJobType("");
        invalid.setPriority(15); // Over max of 10

        mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors").exists())
            .andExpect(jsonPath("$.fieldErrors.jobType").exists())
            .andExpect(jsonPath("$.fieldErrors.priority").exists());
    }

    @Test
    @Order(7)
    @DisplayName("DELETE /api/jobs/{id} → 200 (cancel PENDING job)")
    void cancelJob_pending_returns200() throws Exception {
        // Submit a fresh job to cancel
        JobRequest req = new JobRequest();
        req.setJobType("CANCEL_TEST");
        req.setPriority(5);

        String resp = mockMvc.perform(post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        UUID cancelId = UUID.fromString(
            objectMapper.readTree(resp).get("id").asText());

        mockMvc.perform(delete("/api/jobs/" + cancelId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Job cancelled successfully"));

        // Verify status in DB
        Assertions.assertEquals(JobStatus.CANCELLED,
            jobRepository.findById(cancelId).orElseThrow().getStatus());
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/jobs/dead-letter → 200 empty list")
    void deadLetterJobs_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/jobs/dead-letter"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @Order(9)
    @DisplayName("GET /api/jobs?idempotencyKey=... → 200 with correct job")
    void lookupByIdempotencyKey_returnsJob() throws Exception {
        mockMvc.perform(get("/api/jobs")
                .param("idempotencyKey", IDEMPOTENCY_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(submittedJobId.toString()));
    }
}
