package com.optiqueue.worker.handler;

import com.optiqueue.domain.Job;

/**
 * JobHandler — strategy interface for executing specific job types.
 *
 * Each concrete handler implementation handles one or more job types
 * (e.g., EMAIL_SEND, WEBHOOK_DISPATCH, REPORT_GEN).
 */
public interface JobHandler {

    /**
     * The job type this handler processes (e.g., "EMAIL_SEND").
     */
    String getJobType();

    /**
     * Executes the job business logic.
     *
     * @param job the job entity to process
     * @return a JSON string or text result to be stored in job.result
     * @throws Exception if execution fails (triggers RetryEngine)
     */
    String execute(Job job) throws Exception;
}
