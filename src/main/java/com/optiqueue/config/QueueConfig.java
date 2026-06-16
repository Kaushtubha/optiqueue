package com.optiqueue.config;

import com.optiqueue.worker.JobBlockingQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueueConfig — Spring @Configuration class for infrastructure beans.
 *
 * WHY a separate @Configuration class instead of @Component on JobBlockingQueue?
 *   JobBlockingQueue is a plain Java class (no Spring annotations).
 *   This is intentional: plain Java classes are easier to unit-test
 *   (no Spring context needed), and they're not tied to the framework.
 *   The @Bean method here bridges the gap: Spring manages the lifecycle
 *   (singleton, injection) while the class itself stays framework-free.
 *
 *   This is the "framework at the edges" principle from Clean Architecture:
 *   core logic has no Spring dependencies; Spring wires everything together
 *   at the outermost layer.
 *
 * SINGLE INSTANCE GUARANTEE:
 *   @Bean is @Singleton by default — the same JobBlockingQueue instance
 *   is injected everywhere. SchedulerService (producer) and WorkerPool
 *   (consumers) share the SAME queue object. Essential for the blocking
 *   semantics to work — if they had different instances, workers would
 *   block forever on an empty queue that the scheduler never touches.
 */
@Configuration
public class QueueConfig {

    @Bean
    public JobBlockingQueue jobBlockingQueue(OptiQueueProperties props) {
        int capacity = props.getWorker().getQueueCapacity();
        return new JobBlockingQueue(capacity);
    }
}
