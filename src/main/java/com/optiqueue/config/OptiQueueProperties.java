package com.optiqueue.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OptiQueueProperties — type-safe binding of application.yml config.
 *
 * WHY @ConfigurationProperties over @Value?
 *   @Value("${optiqueue.worker.pool-size}") works for one property,
 *   but @ConfigurationProperties binds an entire nested config tree
 *   into a POJO. Benefits:
 *     - IDE autocomplete in application.yml
 *     - @Validated can enforce constraints (e.g., @Min(1) on poolSize)
 *     - All optiqueue config is in one place — easy to mock in tests
 *     - Renaming a property causes a compile error, not a silent null
 *
 * Maps to application.yml:
 *   optiqueue:
 *     worker:
 *       pool-size: 4
 *       queue-capacity: 100
 *     retry:
 *       max-attempts: 5
 *       initial-delay-ms: 1000
 *     heartbeat:
 *       interval-ms: 5000
 *       ttl-ms: 15000
 */
@Component
@ConfigurationProperties(prefix = "optiqueue")
@Getter
@Setter
public class OptiQueueProperties {

    private Worker worker = new Worker();
    private Retry retry = new Retry();
    private Heartbeat heartbeat = new Heartbeat();

    @Getter @Setter
    public static class Worker {
        /** Number of threads in the ExecutorService worker pool (Step 5). */
        private int poolSize = 4;
        /** Max jobs held in the custom blocking queue between scheduler and workers (Step 4). */
        private int queueCapacity = 100;
    }

    @Getter @Setter
    public static class Retry {
        /** Max attempts before a job moves to DLQ. */
        private int maxAttempts = 5;
        /** Base backoff delay in ms. Doubles each retry (Step 7). */
        private long initialDelayMs = 1000;
    }

    @Getter @Setter
    public static class Heartbeat {
        /** How often workers publish a heartbeat to Redis (Step 9). */
        private long intervalMs = 5000;
        /** How long a missing heartbeat is tolerated before worker = dead (Step 10). */
        private long ttlMs = 15000;
    }
}
