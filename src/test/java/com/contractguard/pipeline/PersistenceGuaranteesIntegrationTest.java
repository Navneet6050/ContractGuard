package com.contractguard.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.contractguard.persistence.RegistryStore;

/**
 * Persistence-level guarantees over the in-memory H2 (test config), exercising the
 * store API the way the HTTP surface does: atomic service registration + audit,
 * atomicity of a guarded decision (a lost race writes NOTHING), decision metadata on
 * auto-published attempts, the attempt's stored canonical/effective forms surviving
 * into the published version row, and the webhook log keeping full failure classifiers.
 * Its own datasource (and therefore its own Spring context) so the exact-count metrics
 * assertions in PublishingWorkflowIntegrationTest are not polluted by these rows.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:persistence-guarantees;DB_CLOSE_DELAY=-1")
class PersistenceGuaranteesIntegrationTest {

    private static final String SLUG = "persist-api";

    @Autowired
    private RegistryStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private static final RegistryStore.AuditEntry REGISTERED =
            new RegistryStore.AuditEntry("SERVICE_REGISTERED", "registered", null, "registered");

    private static final RegistryStore.AuditEntry UPLOADED =
            new RegistryStore.AuditEntry("SPEC_UPLOADED", "pending_ack", null, "uploaded");

    private static final String CANONICAL =
            "{\"openapi\":\"3.0.3\",\"paths\":{\"/widgets\":{\"GET\":{\"response:200\":"
                    + "{\"required\":[\"id\"]}}}}}";
    private static final String EFFECTIVE =
            "{\"GET /widgets :: response:200\":{\"type\":\"object\",\"required\":[\"id\"]}}";

    private long gatedAttempt(String version) {
        return store.insertGatedAttempt(serviceId(), version, "sha-" + version, version + ".yaml",
                "openapi: yaml-for-" + version, CANONICAL, EFFECTIVE, "pending_ack", 1,
                "acknowledge breaking change", "{\"decision\":\"PENDING_ACK\"}",
                List.of(), "uploader", UPLOADED);
    }

    private long serviceId() {
        if (!store.serviceExists(SLUG)) {
            store.registerService(SLUG, "Persistence API", "test", "store-level tests",
                    "tester", REGISTERED);
        }
        return store.findService(SLUG).orElseThrow().id();
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("service registration is atomic: a failed registration leaves no audit row behind")
    void registrationIsAtomic() {
        serviceId();
        assertThat(store.hasAuditEvent(SLUG, "SERVICE_REGISTERED")).isTrue();

        // re-registering the same slug violates the UNIQUE constraint mid-transaction;
        // the audit insert (which happens AFTER the service insert) must roll back with it
        assertThatThrownBy(() -> store.registerService(SLUG, "dup", null, null,
                "tester", REGISTERED))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(store.findService(SLUG)).isPresent();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE service = ? "
                + "AND action = 'SERVICE_REGISTERED'", SLUG)).isEqualTo(1);
    }

    @Test
    @DisplayName("the webhook log keeps full failure classifiers longer than the exception name")
    void notificationStatusIsNotTruncated() {
        String status = "failed: RestClientResponseException";
        assertThat(status.length()).isGreaterThan(32);
        store.insertNotification("published", SLUG, "1.0.0", "{\"service\":\"" + SLUG + "\"}",
                "http://localhost:9/webhook", status, null);

        assertThat(store.listNotifications()).anySatisfy(row -> {
            assertThat(row.service()).isEqualTo(SLUG);
            assertThat(row.status()).isEqualTo(status);
            assertThat(row.deliveredAt()).isNull();
        });
    }

    @Test
    @DisplayName("a decision is guarded by the previous state: a lost race rolls back its transition and audit rows")
    void decisionIsStateGuarded() {
        long attemptId = gatedAttempt("1.0.0");
        long serviceId = serviceId();

        boolean applied = store.decideAndPublish(attemptId, serviceId, SLUG, "1.0.0",
                "pending_ack", "sha-1.0.0", "1.0.0.yaml", "openapi: yaml-for-1.0.0",
                CANONICAL, EFFECTIVE, "published", "decider", "reviewed",
                "SPEC_ACKNOWLEDGED", "acknowledged");
        assertThat(applied).isTrue();
        assertThat(store.findAttempt(attemptId).orElseThrow().state()).isEqualTo("published");

        // replaying the same decision (stale previousState) must fail and write nothing
        assertThatThrownBy(() -> store.decideAndPublish(attemptId, serviceId, SLUG, "1.0.0",
                "pending_ack", "sha-1.0.0", "1.0.0.yaml", "openapi: yaml-for-1.0.0",
                CANONICAL, EFFECTIVE, "published", "racer", "second review",
                "SPEC_ACKNOWLEDGED", "concurrent decision"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM attempt_transitions WHERE attempt_id = ?",
                attemptId)).isEqualTo(2); // received->pending_ack + pending_ack->published
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE attempt_id = ?", attemptId))
                .isEqualTo(2); // SPEC_UPLOADED + SPEC_ACKNOWLEDGED
        // only the decided version is published for this service, exactly once
        assertThat(store.listVersions(serviceId).stream()
                .filter(v -> v.version().equals("1.0.0")))
                .hasSize(1)
                .allSatisfy(v -> assertThat(v.state()).isEqualTo("published"));

        var decided = store.findAttempt(attemptId).orElseThrow();
        assertThat(decided.decidedBy()).isEqualTo("decider");
        assertThat(decided.decidedAt()).isNotNull();
    }

    @Test
    @DisplayName("auto-published attempts are decided by the upload itself (decided_at/decided_by set)")
    void autoPublishCarriesDecisionMetadata() {
        long serviceId = serviceId();
        long attemptId = store.autoPublishAttempt(serviceId, "2.0.0", "sha-2.0.0", "2.0.0.yaml",
                "openapi: yaml-for-2.0.0", CANONICAL, EFFECTIVE, "uploader",
                "{\"decision\":\"PUBLISH\"}", List.of(), "uploader",
                new RegistryStore.AuditEntry("SPEC_UPLOADED", "auto_published", null,
                        "published automatically"));

        var attempt = store.findAttempt(attemptId).orElseThrow();
        assertThat(attempt.state()).isEqualTo("auto_published");
        assertThat(attempt.decidedAt()).isNotNull();
        assertThat(attempt.decidedBy()).isEqualTo("uploader");
        // changelog timestamps come from the version row; the attempt itself is decided
        assertThat(store.listVersions(serviceId)).anySatisfy(v -> {
            assertThat(v.version()).isEqualTo("2.0.0");
            assertThat(v.publishedBy()).isEqualTo("uploader");
            assertThat(v.publishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a later decision publishes the canonical/effective forms the gate evaluated, with provenance")
    void decisionPublishesWhatTheGateSaw() {
        long attemptId = gatedAttempt("3.0.0");
        long serviceId = serviceId();
        var before = store.findAttempt(attemptId).orElseThrow();
        assertThat(before.canonicalJson()).isEqualTo(CANONICAL);
        assertThat(before.effectiveJson()).isEqualTo(EFFECTIVE);
        assertThat(before.sourceFilename()).isEqualTo("3.0.0.yaml");

        store.decideAndPublish(attemptId, serviceId, SLUG, "3.0.0", "pending_ack",
                before.sourceSha(), before.sourceFilename(), before.rawYaml(),
                before.canonicalJson(), before.effectiveJson(), "overridden_published",
                "overrider", "documented decision", "SPEC_OVERRIDDEN", "override");

        var published = store.findVersion(serviceId, "3.0.0").orElseThrow();
        assertThat(published.canonicalJson()).isEqualTo(CANONICAL);
        assertThat(published.effectiveJson()).isEqualTo(EFFECTIVE);
        assertThat(published.sourceFilename()).isEqualTo("3.0.0.yaml");
        assertThat(published.publishedBy()).isEqualTo("overrider");
        assertThat(published.state()).isEqualTo("published");
    }
}
