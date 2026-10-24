package com.optiqueue.worker.handler;

import com.optiqueue.domain.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DefaultJobHandler — fallback handler for arbitrary job types.
 */
@Component
@Slf4j
public class DefaultJobHandler implements JobHandler {

    @Override
    public String getJobType() {
        return "*"; // Matches any job type
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
