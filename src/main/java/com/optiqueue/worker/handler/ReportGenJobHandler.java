package com.optiqueue.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiqueue.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ReportGenJobHandler — processes async data calculation and report generation jobs.
 */
@Component
public class ReportGenJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportGenJobHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getJobType() {
        return "REPORT_GEN";
    }

    @Override
    public String execute(Job job) throws Exception {
        String payload = job.getPayload();
        String reportType = "MONTHLY_SUMMARY";

        if (payload != null && !payload.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(payload);
                if (node.has("reportType")) {
                    reportType = node.get("reportType").asText();
                }
            } catch (Exception ignored) {
            }
        }

        // Simulate report generation workload (200-500ms)
        long duration = 200 + (long) (Math.random() * 300);
        Thread.sleep(duration);

        String reportId = "rep-" + UUID.randomUUID().toString().substring(0, 8);
        int rowsProcessed = 1000 + (int) (Math.random() * 9000);

        log.info("Report generated: id={}, type={}, rows={}", reportId, reportType, rowsProcessed);

        return String.format(
            "{\"status\":\"GENERATED\",\"reportId\":\"%s\",\"type\":\"%s\",\"rowsProcessed\":%d,\"durationMs\":%d}",
            reportId, reportType, rowsProcessed, duration
        );
    }
}
