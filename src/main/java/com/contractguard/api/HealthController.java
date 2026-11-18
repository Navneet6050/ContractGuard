package com.contractguard.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe: confirms the HTTP layer, the H2 database and the webhook
 * receiver configuration are reachable/configured.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final String webhookUrl;

    public HealthController(JdbcTemplate jdbc,
                            @Value("${contractguard.webhook-url}") String webhookUrl) {
        this.jdbc = jdbc;
        this.webhookUrl = webhookUrl;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "contractguard");
        body.put("time", Instant.now().toString());
        body.put("webhook_url", webhookUrl);
        body.put("db", databaseStatus());
        return body;
    }

    private String databaseStatus() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN: " + e.getClass().getSimpleName();
        }
    }
}
