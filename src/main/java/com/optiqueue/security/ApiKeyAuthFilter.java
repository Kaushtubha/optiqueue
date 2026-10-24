package com.optiqueue.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * ApiKeyAuthFilter — stateless API key authentication.
 *
 * Every request to /api/** must carry the header:
 *   X-API-Key: <configured-secret>
 *
 * WHY API keys over JWT for an internal job queue?
 *   JWT is session-based (issued on login, carries claims, expires).
 *   API keys are credential-based (pre-shared, long-lived, per-client).
 *   For machine-to-machine communication (microservices, CI pipelines),
 *   API keys are simpler and have no token expiry to manage.
 *   Production rule: use API keys for service-to-service, JWT for user sessions.
 *
 * INTERVIEW Q: "What's the difference between authentication and authorization?"
 *   Authentication: "Who are you?" — verified by the API key check below.
 *   Authorization: "What can you do?" — would be role-based (admin can see DLQ,
 *   client can only submit jobs). We have no RBAC here — just authn.
 *
 * INTERVIEW Q: "How do you rotate API keys without downtime?"
 *   The API_KEYS env var is a comma-separated list. Add the new key,
 *   deploy, wait for clients to switch, remove the old key, redeploy.
 *   Zero downtime because we support multiple valid keys simultaneously.
 *
 * EXTENDS OncePerRequestFilter:
 *   Guarantees the filter runs ONCE per request (not twice if forwarded).
 *   Spring's filter chain can call filters multiple times on internal forwards;
 *   OncePerRequestFilter prevents that.
 */
@Component
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    // Paths excluded from API key check
    private static final Set<String> EXCLUDED_PATHS = Set.of(
        "/actuator/health",
        "/actuator/info",
        "/actuator/metrics"
    );

    /**
     * Comma-separated list of valid API keys from environment.
     * e.g., API_KEYS=key-prod-abc123,key-ci-def456
     *
     * Default "disabled" means auth is off in local dev.
     * In production, always set this env var to a non-"disabled" value.
     */
    @Value("${security.api-keys:disabled}")
    private String apiKeysConfig;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain) throws ServletException, IOException {

        // Only enforce API key authentication on /api/** endpoints (skip static web UI and actuator)
        if (!path.startsWith("/api") || isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Auth disabled in local dev (API_KEYS not set)
        if ("disabled".equalsIgnoreCase(apiKeysConfig)) {
            chain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader("X-API-Key");

        if (providedKey == null || !isValidKey(providedKey)) {
            log.warn("Rejected request to {} — missing or invalid API key", path);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"error":"Unauthorized","message":"Valid X-API-Key header required"}
                """);
            return; // Do NOT call chain.doFilter — request is rejected
        }

        // Key is valid — proceed
        chain.doFilter(request, response);
    }

    private boolean isValidKey(String key) {
        Set<String> validKeys = Set.of(apiKeysConfig.split(","));
        return validKeys.contains(key.strip());
    }

    private boolean isExcluded(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }
}
