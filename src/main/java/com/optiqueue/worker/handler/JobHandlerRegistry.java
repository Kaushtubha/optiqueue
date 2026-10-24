package com.optiqueue.worker.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JobHandlerRegistry — discovers and routes jobs to the appropriate JobHandler.
 */
@Component
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final JobHandler defaultHandler;

    public JobHandlerRegistry(List<JobHandler> handlerList, DefaultJobHandler defaultHandler) {
        this.defaultHandler = defaultHandler;
        for (JobHandler handler : handlerList) {
            if (!"*".equals(handler.getJobType())) {
                handlers.put(handler.getJobType().toUpperCase(), handler);
                log.info("Registered JobHandler for jobType: {}", handler.getJobType().toUpperCase());
            }
        }
    }

    /**
     * Finds the matching handler for a given job type, falling back to DefaultJobHandler.
     */
    public JobHandler getHandler(String jobType) {
        if (jobType == null) {
            return defaultHandler;
        }
        JobHandler handler = handlers.get(jobType.toUpperCase());
        return handler != null ? handler : defaultHandler;
    }
}
