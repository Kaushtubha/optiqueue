package com.optiqueue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OptiQueueApplication — entry point for the Spring Boot application.
 *
 * @SpringBootApplication is a convenience annotation that combines:
 *   - @Configuration: this class can define @Bean methods
 *   - @EnableAutoConfiguration: Spring Boot reads classpath and auto-configures
 *     DataSource, JPA, Redis, web server etc. based on what jars are present
 *   - @ComponentScan: scans com.optiqueue.* for @Component, @Service, @Repository
 *
 * @EnableScheduling: activates Spring's @Scheduled task executor.
 * We use this in Step 10 for the heartbeat monitor that detects dead workers.
 * Without this annotation, @Scheduled methods are silently ignored — a common gotcha.
 */
@SpringBootApplication
@EnableScheduling
public class OptiQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(OptiQueueApplication.class, args);
    }
}
