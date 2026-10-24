package com.optiqueue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OptiQueueProperties — type-safe binding of application.yml config.
 */
@Component
@ConfigurationProperties(prefix = "optiqueue")
public class OptiQueueProperties {

    private Worker worker = new Worker();
    private Retry retry = new Retry();
    private Heartbeat heartbeat = new Heartbeat();

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(Heartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    public static class Worker {
        private int poolSize = 4;
        private int queueCapacity = 100;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Retry {
        private int maxAttempts = 5;
        private long initialDelayMs = 1000;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }
    }

    public static class Heartbeat {
        private long intervalMs = 5000;
        private long ttlMs = 15000;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getTTLMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        public long getTtlMs() {
            return ttlMs;
        }
    }
}
