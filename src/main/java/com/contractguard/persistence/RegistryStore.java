package com.contractguard.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * All H2 persistence for the registry and publishing workflow (JdbcTemplate over the
 * PLAN.md §4 schema, thin and explicit — no business logic). Every multi-row write is
 * one transaction so an attempt and its findings/impacts/audit rows commit atomically.
 */
@Repository
public class RegistryStore {

    private final JdbcTemplate jdbc;

    public RegistryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ rows

    public record ServiceRow(long id, String slug, String title, String ownerTeam,
                             String description, Instant createdAt) {
    }

    public record ServiceOverview(long id, String slug, String title, String ownerTeam,
                                  String description, Instant createdAt,
                                  String currentVersion, int versionCount) {
    }

    public record VersionSummary(String version, String sourceSha, String sourceFilename,
                                 String state, Instant publishedAt, String publishedBy) {
    }

    public record SpecVersionRow(long id, long serviceId, String version, String sourceSha,
                                 String sourceFilename, String rawYaml, String canonicalJson,
                                 String effectiveJson, String state, Instant publishedAt,
                                 String publishedBy) {
    }

    public record AttemptRow(long id, long serviceId, String incomingVersion, String sourceSha,
                             String rawYaml, String canonicalJson, String state,
                             int breakingCount, String requiredAction, String decisionJson,
                             Instant createdAt, Instant decidedAt, String decidedBy,
                             String justification, String note) {
    }

    public record AttemptSummary(long id, String incomingVersion, String state, int breakingCount,
                                 Instant createdAt, Instant decidedAt, String decidedBy,
                                 String justification) {
    }

    public record StoredFinding(long id, String code, String severity, String scope,
                                String method, String path, String location, String fieldPath,
                                String detail, String oldJson, String newJson) {
    }

    public record StoredImpact(long findingId, String consumer, String level, String reason) {
    }

    public record AuditRow(long id, String service, String version, Long attemptId,
                           String action, String decision, String actor, String justification,
                           String details, Instant createdAt) {
    }

    public record NotificationRow(long id, String kind, String service, String version,
                                  String payloadJson, String targetUrl, String status,
                                  Instant createdAt, Instant deliveredAt) {
    }

    /** One finding plus its per-consumer impact rows, persisted with the attempt. */
    public record FindingWithImpacts(String code, String severity, String scope, String method,
                                     String path, String location, String fieldPath, String detail,
                                     String oldJson, String newJson, List<ImpactEntry> impacts) {

        /** {@code level} is the lowercase stored tier (schema CHECK constraint). */
        public record ImpactEntry(String consumer, String level, String reason) {
        }
    }

    /** Audit details written atomically with the rows they describe. */
    public record AuditEntry(String action, String decision, String justification, String details) {
    }

    public Optional<String> serviceSlug(long serviceId) {
        return jdbc.query("SELECT slug FROM services WHERE id = ?",
                (rs, i) -> rs.getString("slug"), serviceId).stream().findFirst();
    }

    /** True when the given audit action already exists for the service (kit-import marker). */
    public boolean hasAuditEvent(String service, String action) {
        Boolean exists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM audit_events WHERE service = ? AND action = ?",
                Boolean.class, service, action);
        return Boolean.TRUE.equals(exists);
    }

    // ------------------------------------------------------------------ services

    public int countServices() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        return count == null ? 0 : count;
    }

    public boolean serviceExists(String slug) {
        Boolean exists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM services WHERE slug = ?", Boolean.class, slug);
        return Boolean.TRUE.equals(exists);
    }

    public long insertService(String slug, String title, String ownerTeam, String description) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO services (slug, title, owner_team, description) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, slug);
            ps.setString(2, title);
            ps.setString(3, ownerTeam);
            ps.setString(4, description);
            return ps;
        }, keys);
        return keys.getKey() == null ? -1 : keys.getKey().longValue();
    }

    private static final String SERVICE_COLUMNS =
            "id, slug, title, owner_team, description, created_at";

    public Optional<ServiceRow> findService(String slug) {
        return jdbc.query("SELECT " + SERVICE_COLUMNS + " FROM services WHERE slug = ?",
                RegistryStore::serviceRow, slug).stream().findFirst();
    }

    public List<ServiceOverview> listServices() {
        return jdbc.query("""
                        SELECT s.id, s.slug, s.title, s.owner_team, s.description, s.created_at,
                               cur.version AS current_version,
                               (SELECT COUNT(*) FROM spec_versions v WHERE v.service_id = s.id) AS version_count
                        FROM services s
                        LEFT JOIN spec_versions cur ON cur.service_id = s.id AND cur.state = 'published'
                        ORDER BY s.slug
                        """,
                (rs, i) -> new ServiceOverview(rs.getLong("id"), rs.getString("slug"),
                        rs.getString("title"), rs.getString("owner_team"),
                        rs.getString("description"), toInstant(rs.getTimestamp("created_at")),
                        rs.getString("current_version"), rs.getInt("version_count")));
    }

    private static ServiceRow serviceRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ServiceRow(rs.getLong("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("owner_team"), rs.getString("description"),
                toInstant(rs.getTimestamp("created_at")));
    }

    // ------------------------------------------------------------------ spec versions

    private static final String VERSION_COLUMNS =
            "id, service_id, version, source_sha, source_filename, raw_yaml, canonical_json, "
                    + "effective_json, state, published_at, published_by";

    public Optional<SpecVersionRow> findVersion(long serviceId, String version) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM spec_versions "
                        + "WHERE service_id = ? AND version = ?",
                RegistryStore::versionRow, serviceId, version).stream().findFirst();
    }

    public Optional<SpecVersionRow> currentVersion(long serviceId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM spec_versions "
                        + "WHERE service_id = ? AND state = 'published' ORDER BY published_at DESC LIMIT 1",
                RegistryStore::versionRow, serviceId).stream().findFirst();
    }

    public List<VersionSummary> listVersions(long serviceId) {
        return jdbc.query("""
                        SELECT version, source_sha, source_filename, state, published_at, published_by
                        FROM spec_versions WHERE service_id = ? ORDER BY published_at DESC, id DESC
                        """,
                (rs, i) -> new VersionSummary(rs.getString("version"), rs.getString("source_sha"),
                        rs.getString("source_filename"), rs.getString("state"),
                        toInstant(rs.getTimestamp("published_at")), rs.getString("published_by")),
                serviceId);
    }

    /**
     * Supersedes the currently published version(s) and stores the new one as published,
     * in one transaction (the version row exists only once the version is truly current).
     */
    @Transactional
    public void publishVersion(long serviceId, String version, String sourceSha, String sourceFilename,
                               String rawYaml, String canonicalJson, String effectiveJson,
                               String publishedBy) {
        jdbc.update("UPDATE spec_versions SET state = 'superseded' "
                + "WHERE service_id = ? AND state = 'published'", serviceId);
        jdbc.update("""
                        INSERT INTO spec_versions (service_id, version, source_sha, source_filename,
                            raw_yaml, canonical_json, effective_json, state, published_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'published', ?)
                        """,
                serviceId, version, sourceSha, sourceFilename, rawYaml, canonicalJson,
                effectiveJson, publishedBy);
    }

    private static SpecVersionRow versionRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SpecVersionRow(rs.getLong("id"), rs.getLong("service_id"),
                rs.getString("version"), rs.getString("source_sha"), rs.getString("source_filename"),
                rs.getString("raw_yaml"), rs.getString("canonical_json"), rs.getString("effective_json"),
                rs.getString("state"), toInstant(rs.getTimestamp("published_at")),
                rs.getString("published_by"));
    }

    // ------------------------------------------------------------------ attempts

    private static final String ATTEMPT_COLUMNS =
            "id, service_id, incoming_version, source_sha, raw_yaml, canonical_json, state, "
                    + "breaking_count, required_action, decision_json, created_at, decided_at, "
                    + "decided_by, justification, note";

    /**
     * Persists a gated attempt with its findings, per-consumer impacts and audit trail in
     * one transaction. {@code state} is a terminal-for-now gate state (pending_ack /
     * blocked) or auto_published (which then still needs its version row — the caller
     * invokes {@link #publishVersion} in the same logical step via
     * {@link #publishGatedAttempt}).
     */
    @Transactional
    public long insertGatedAttempt(long serviceId, String incomingVersion, String sourceSha,
                                   String rawYaml, String canonicalJson, String state,
                                   int breakingCount, String requiredAction, String decisionJson,
                                   List<FindingWithImpacts> findings, String actor, AuditEntry audit) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                            INSERT INTO publication_attempts (service_id, incoming_version, source_sha,
                                raw_yaml, canonical_json, state, breaking_count, required_action, decision_json)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    new String[]{"id"});
            ps.setLong(1, serviceId);
            ps.setString(2, incomingVersion);
            ps.setString(3, sourceSha);
            ps.setString(4, rawYaml);
            ps.setString(5, canonicalJson);
            ps.setString(6, state);
            ps.setInt(7, breakingCount);
            ps.setString(8, requiredAction);
            ps.setString(9, decisionJson);
            return ps;
        }, keys);
        long attemptId = keys.getKey() == null ? -1 : keys.getKey().longValue();

        insertFindings(attemptId, findings);
        jdbc.update("""
                        INSERT INTO attempt_transitions (attempt_id, from_state, to_state, actor)
                        VALUES (?, 'received', ?, ?)
                        """, attemptId, state, actor);
        jdbc.update("""
                        INSERT INTO audit_events (service, version, attempt_id, action, decision, actor, justification, details)
                        VALUES ((SELECT slug FROM services WHERE id = ?), ?, ?, ?, ?, ?, ?, ?)
                        """, serviceId, incomingVersion, attemptId, audit.action(), audit.decision(),
                actor, audit.justification(), audit.details());
        return attemptId;
    }

    /** Persists a rejected attempt (invalid spec / duplicate / regression) with its audit row. */
    @Transactional
    public long insertRejectedAttempt(long serviceId, String incomingVersion, String sourceSha,
                                      String rawYaml, String note, String actor, AuditEntry audit) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                            INSERT INTO publication_attempts (service_id, incoming_version, source_sha,
                                raw_yaml, state, breaking_count, note)
                            VALUES (?, ?, ?, ?, 'rejected', 0, ?)
                            """,
                    new String[]{"id"});
            ps.setLong(1, serviceId);
            ps.setString(2, incomingVersion);
            ps.setString(3, sourceSha);
            ps.setString(4, rawYaml);
            ps.setString(5, note);
            return ps;
        }, keys);
        long attemptId = keys.getKey() == null ? -1 : keys.getKey().longValue();
        jdbc.update("INSERT INTO attempt_transitions (attempt_id, from_state, to_state, actor) "
                + "VALUES (?, 'received', 'rejected', ?)", attemptId, actor);
        jdbc.update("""
                        INSERT INTO audit_events (service, version, attempt_id, action, decision, actor, justification, details)
                        VALUES ((SELECT slug FROM services WHERE id = ?), ?, ?, ?, ?, ?, ?, ?)
                        """, serviceId, incomingVersion, attemptId, audit.action(), audit.decision(),
                actor, audit.justification(), audit.details());
        return attemptId;
    }

    /**
     * Rule-1 auto-publication: gated attempt (no breaking findings) + published spec
     * version row in ONE transaction, so "published" is never visible without its
     * findings/audit trail and vice versa.
     */
    @Transactional
    public long autoPublishAttempt(long serviceId, String incomingVersion, String sourceSha,
                                   String sourceFilename, String rawYaml, String canonicalJson,
                                   String effectiveJson, String publishedBy, String decisionJson,
                                   List<FindingWithImpacts> findings, String actor, AuditEntry audit) {
        long attemptId = insertGatedAttempt(serviceId, incomingVersion, sourceSha, rawYaml,
                canonicalJson, "auto_published", 0, "none: publication permitted", decisionJson,
                findings, actor, audit);
        publishVersion(serviceId, incomingVersion, sourceSha, sourceFilename, rawYaml,
                canonicalJson, effectiveJson, publishedBy);
        return attemptId;
    }

    /**
     * Completes a human decision: appends the guarded transition, updates the attempt
     * state, writes the audit row and (for publish outcomes) stores the spec version —
     * atomically. The transition row is written BEFORE the state update so its
     * {@code from_state} is the genuine previous state.
     */
    @Transactional
    public void decideAndPublish(long attemptId, long serviceId, String slug, String version,
                                 String previousState, String sourceSha, String sourceFilename,
                                 String rawYaml, String canonicalJson, String effectiveJson,
                                 String newState, String actor, String justification,
                                 String auditAction, String auditDetails) {
        jdbc.update("INSERT INTO attempt_transitions (attempt_id, from_state, to_state, actor, comment) "
                        + "VALUES (?, ?, ?, ?, ?)",
                attemptId, previousState, newState, actor, justification);
        jdbc.update("UPDATE publication_attempts SET state = ?, decided_at = CURRENT_TIMESTAMP, "
                + "decided_by = ?, justification = COALESCE(?, justification) WHERE id = ?",
                newState, actor, justification, attemptId);
        jdbc.update("INSERT INTO audit_events (service, version, attempt_id, action, decision, actor, justification, details) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", slug, version, attemptId, auditAction, newState,
                actor, justification, auditDetails);
        if (canonicalJson != null) {
            jdbc.update("UPDATE spec_versions SET state = 'superseded' "
                    + "WHERE service_id = ? AND state = 'published'", serviceId);
            jdbc.update("""
                            INSERT INTO spec_versions (service_id, version, source_sha, source_filename,
                                raw_yaml, canonical_json, effective_json, state, published_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'published', ?)
                            """,
                    serviceId, version, sourceSha, sourceFilename, rawYaml, canonicalJson,
                    effectiveJson, actor);
        }
    }

    private void insertFindings(long attemptId, List<FindingWithImpacts> findings) {
        for (FindingWithImpacts finding : findings) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                                INSERT INTO findings (attempt_id, code, severity, scope, method, path,
                                    location, field_path, detail, old_json, new_json)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        new String[]{"id"});
                ps.setLong(1, attemptId);
                ps.setString(2, finding.code());
                ps.setString(3, finding.severity());
                ps.setString(4, finding.scope());
                ps.setString(5, finding.method());
                ps.setString(6, finding.path());
                ps.setString(7, finding.location());
                ps.setString(8, finding.fieldPath());
                ps.setString(9, finding.detail());
                ps.setString(10, finding.oldJson());
                ps.setString(11, finding.newJson());
                return ps;
            }, keys);
            long findingId = keys.getKey() == null ? -1 : keys.getKey().longValue();
            for (FindingWithImpacts.ImpactEntry impact : finding.impacts()) {
                jdbc.update("INSERT INTO finding_impacts (finding_id, consumer, level, evidence_json) "
                        + "VALUES (?, ?, ?, ?)", findingId, impact.consumer(), impact.level(),
                        impact.reason());
            }
        }
    }

    public Optional<AttemptRow> findAttempt(long attemptId) {
        return jdbc.query("SELECT " + ATTEMPT_COLUMNS + " FROM publication_attempts WHERE id = ?",
                RegistryStore::attemptRow, attemptId).stream().findFirst();
    }

    public List<AttemptSummary> listAttempts(long serviceId) {
        return jdbc.query("""
                        SELECT id, incoming_version, state, breaking_count, created_at, decided_at,
                               decided_by, justification
                        FROM publication_attempts WHERE service_id = ? ORDER BY id DESC
                        """,
                (rs, i) -> new AttemptSummary(rs.getLong("id"), rs.getString("incoming_version"),
                        rs.getString("state"), rs.getInt("breaking_count"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("decided_at")), rs.getString("decided_by"),
                        rs.getString("justification")), serviceId);
    }

    private static AttemptRow attemptRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AttemptRow(rs.getLong("id"), rs.getLong("service_id"),
                rs.getString("incoming_version"), rs.getString("source_sha"), rs.getString("raw_yaml"),
                rs.getString("canonical_json"), rs.getString("state"), rs.getInt("breaking_count"),
                rs.getString("required_action"), rs.getString("decision_json"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("decided_at")),
                rs.getString("decided_by"), rs.getString("justification"), rs.getString("note"));
    }

    // ------------------------------------------------------------------ findings + impacts

    public List<StoredFinding> findingsForAttempt(long attemptId) {
        return jdbc.query("""
                        SELECT id, code, severity, scope, method, path, location, field_path, detail,
                               old_json, new_json
                        FROM findings WHERE attempt_id = ? ORDER BY id
                        """,
                (rs, i) -> new StoredFinding(rs.getLong("id"), rs.getString("code"),
                        rs.getString("severity"), rs.getString("scope"), rs.getString("method"),
                        rs.getString("path"), rs.getString("location"), rs.getString("field_path"),
                        rs.getString("detail"), rs.getString("old_json"), rs.getString("new_json")),
                attemptId);
    }

    public List<StoredImpact> impactsForAttempt(long attemptId) {
        return jdbc.query("""
                        SELECT fi.finding_id, fi.consumer, fi.level, fi.evidence_json
                        FROM finding_impacts fi JOIN findings f ON fi.finding_id = f.id
                        WHERE f.attempt_id = ? ORDER BY fi.id
                        """,
                (rs, i) -> new StoredImpact(rs.getLong("finding_id"), rs.getString("consumer"),
                        rs.getString("level"), rs.getString("evidence_json")),
                attemptId);
    }

    public List<String> affectedConsumers(long attemptId) {
        return jdbc.query("""
                        SELECT DISTINCT fi.consumer FROM finding_impacts fi
                        JOIN findings f ON fi.finding_id = f.id
                        WHERE f.attempt_id = ? AND fi.level = 'affected' ORDER BY fi.consumer
                        """,
                (rs, i) -> rs.getString("consumer"), attemptId);
    }

    // ------------------------------------------------------------------ audit

    public void insertAuditEvent(String service, String version, Long attemptId, String action,
                                 String decision, String actor, String justification, String details) {
        jdbc.update("""
                        INSERT INTO audit_events (service, version, attempt_id, action, decision, actor, justification, details)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, service, version, attemptId, action, decision, actor, justification, details);
    }

    public List<AuditRow> listAudit(String service, Long attemptId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, service, version, attempt_id, action, decision, actor, justification, details, created_at
                FROM audit_events WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (service != null && !service.isBlank()) {
            sql.append(" AND service = ?");
            params.add(service.trim());
        }
        if (attemptId != null) {
            sql.append(" AND attempt_id = ?");
            params.add(attemptId);
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        return jdbc.query(sql.toString(), (rs, i) -> new AuditRow(rs.getLong("id"),
                rs.getString("service"), rs.getString("version"),
                rs.getObject("attempt_id") == null ? null : rs.getLong("attempt_id"),
                rs.getString("action"), rs.getString("decision"), rs.getString("actor"),
                rs.getString("justification"), rs.getString("details"),
                toInstant(rs.getTimestamp("created_at"))), params.toArray());
    }

    // ------------------------------------------------------------------ notifications

    public void insertNotification(String kind, String service, String version, String payloadJson,
                                   String targetUrl, String status, Instant deliveredAt) {
        jdbc.update("""
                        INSERT INTO notifications (kind, service, version, payload_json, target_url, status, delivered_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, kind, service, version, payloadJson, targetUrl, status,
                deliveredAt == null ? null : Timestamp.from(deliveredAt));
    }

    public List<NotificationRow> listNotifications() {
        return jdbc.query("""
                        SELECT id, kind, service, version, payload_json, target_url, status,
                               created_at, delivered_at
                        FROM notifications ORDER BY id DESC LIMIT 200
                        """,
                (rs, i) -> new NotificationRow(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("service"), rs.getString("version"), rs.getString("payload_json"),
                        rs.getString("target_url"), rs.getString("status"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("delivered_at"))));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
