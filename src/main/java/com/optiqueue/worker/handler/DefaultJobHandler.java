package com.optiqueue.worker.handler;

import com.optiqueue.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DefaultJobHandler — fallback handler for arbitrary job types.
 */
@Component
public class DefaultJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobHandler.class);

    @Override
    public String getJobType() {
        return "*";
    }

    @Override
    public String execute(Job job) throws Exception {
        long duration = 100 + (long) (Math.random() * 250);
        Thread.sleep(duration);

        log.info("Executed generic job id={} type={}", job.getId(), job.getJobType());

        return String.format(
            "{\"status\":\"COMPLETED\",\"jobType\":\"%s\",\"durationMs\":%d}",
            job.getJobType(), duration
        );
    }
}
