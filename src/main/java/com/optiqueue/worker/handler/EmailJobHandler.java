package com.optiqueue.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiqueue.domain.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EmailJobHandler — processes transactional email dispatch jobs.
 */
@Component
@Slf4j
public class EmailJobHandler implements JobHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getJobType() {
        return "EMAIL_SEND";
    }

    @Override
    public String execute(Job job) throws Exception {
        String payload = job.getPayload();
        String to = "unknown@example.com";
        String subject = "Default Subject";

        if (payload != null && !payload.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(payload);
                if (node.has("to")) {
                    to = node.get("to").asText();
                }
                if (node.has("subject")) {
                    subject = node.get("subject").asText();
                }
            } catch (Exception e) {
                log.warn("Payload not JSON or missing fields, using raw payload: {}", payload);
            }
        }

        // Simulate network latency for SMTP / SendGrid API call (100-300ms)
        long latency = 100 + (long) (Math.random() * 200);
        Thread.sleep(latency);

        String messageId = "msg-" + UUID.randomUUID();
        log.info("Email delivered to '{}' with subject '{}' (messageId={})", to, subject, messageId);

        return String.format(
            "{\"status\":\"SENT\",\"messageId\":\"%s\",\"recipient\":\"%s\",\"subject\":\"%s\",\"durationMs\":%d}",
            messageId, to, subject, latency
        );
    }
}
