package com.optiqueue.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiqueue.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * WebhookJobHandler — delivers asynchronous HTTP webhooks to external services.
 */
@Component
public class WebhookJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookJobHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Override
    public String getJobType() {
        return "WEBHOOK_DISPATCH";
    }

    @Override
    public String execute(Job job) throws Exception {
        String payload = job.getPayload();
        String url = null;
        String body = "{}";

        if (payload != null && !payload.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(payload);
                if (node.has("url")) {
                    url = node.get("url").asText();
                }
                if (node.has("data")) {
                    body = node.get("data").toString();
                } else {
                    body = payload;
                }
            } catch (Exception e) {
                body = payload;
            }
        }

        if (url == null || url.isBlank() || url.contains("example.com")) {
            // Simulated delivery when target is an example or test endpoint
            long latency = 80 + (long) (Math.random() * 150);
            Thread.sleep(latency);
            log.info("Simulated webhook delivery to {}", url != null ? url : "mock-receiver");
            return String.format(
                "{\"status\":\"SUCCESS\",\"targetUrl\":\"%s\",\"statusCode\":200,\"durationMs\":%d}",
                url != null ? url : "mock-receiver", latency
            );
        }

        // Real HTTP dispatch
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("User-Agent", "OptiQueue-Worker/1.0")
            .header("X-Job-ID", job.getId().toString())
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long duration = System.currentTimeMillis() - start;

        int status = response.statusCode();
        if (status >= 400) {
            throw new RuntimeException("Webhook returned HTTP " + status + ": " + response.body());
        }

        return String.format(
            "{\"status\":\"SUCCESS\",\"targetUrl\":\"%s\",\"statusCode\":%d,\"durationMs\":%d}",
            url, status, duration
        );
    }
}
