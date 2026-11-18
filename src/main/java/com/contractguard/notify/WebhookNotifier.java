package com.contractguard.notify;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.contractguard.persistence.RegistryStore;

/**
 * Fire-and-forget webhook delivery to the untouched starter-kit receiver (PLAN.md §8,
 * DECISIONS.md B-09). Delivery is attempted with short timeouts; any failure is recorded
 * in the notifications table and logged — it can never fail an already committed
 * publication. The caller must invoke this AFTER the transactional persistence has
 * committed (this class does no persistence of the publication itself).
 */
@Component
public class WebhookNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient client;
    private final String targetUrl;
    private final RegistryStore store;

    public WebhookNotifier(@Value("${contractguard.webhook-url}") String targetUrl,
                           RegistryStore store) {
        this.targetUrl = targetUrl;
        this.store = store;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Delivers one notification. Non-fatal by contract: every failure mode (connect,
     * timeout, non-2xx) is swallowed into the notifications log. Even the logging of a
     * failure can never propagate (the schema's status column is VARCHAR(32), so the
     * status is a compact classifier).
     *
     * @param kind    event name, e.g. "published", "overridden_published", "blocked"
     * @param payload fully built payload (service, version, findings summary, ...)
     */
    public void deliver(String kind, String service, String version, String payloadJson) {
        String status;
        Instant deliveredAt;
        try {
            client.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            status = "delivered";
            deliveredAt = Instant.now();
            LOG.info("webhook {} for {}/{} delivered to {}", kind, service, version, targetUrl);
        } catch (Exception e) {
            status = ("failed: " + e.getClass().getSimpleName());
            deliveredAt = null;
            LOG.warn("webhook {} for {}/{} could not be delivered to {}: {}", kind, service,
                    version, targetUrl, e.toString());
        }
        try {
            store.insertNotification(kind, service, version, payloadJson, targetUrl, status,
                    deliveredAt);
        } catch (Exception e) {
            LOG.warn("webhook outcome could not be recorded: {}", e.toString());
        }
    }

    public String targetUrl() {
        return targetUrl;
    }
}
