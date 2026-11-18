package com.contractguard.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.contractguard.api.ConflictException;
import com.contractguard.api.InvalidRequestException;
import com.contractguard.api.NotFoundException;
import com.contractguard.api.SpecInvalidException;
import com.contractguard.api.ValidationException;
import com.contractguard.compatibility.CompatibilityEngine;
import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.Severity;
import com.contractguard.consumers.ConsumerRegistry;
import com.contractguard.domain.DecisionResult;
import com.contractguard.domain.Decision;
import com.contractguard.domain.PublishingDecision;
import com.contractguard.notify.WebhookNotifier;
import com.contractguard.persistence.RegistryStore;
import com.contractguard.policy.PolicyFinding;
import com.contractguard.policy.Semver;
import com.contractguard.policy.VersionPolicyAnalyzer;
import com.contractguard.specs.DeprecationRecord;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParseException;
import com.contractguard.specs.SpecParser;
import com.contractguard.specs.SpecValidationError;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/**
 * Publication pipeline orchestration (PLAN.md §3 steps 1-11): receive → parse/validate →
 * duplicate/regression check → diff (+ deprecation scan) → version policy → consumer
 * impact → gate → audit → notify. Owns transaction boundaries (via {@link RegistryStore})
 * and I/O ordering; all analysis is delegated to the pure core packages.
 *
 * Webhooks are delivered strictly AFTER the transactional persistence has returned, so a
 * delivery failure can never undo or fail a committed publication.
 */
@Service
public class PublicationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int WEBHOOK_FINDING_LIMIT = 20;

    private final RegistryStore store;
    private final SpecParser specParser;
    private final CompatibilityEngine engine = new CompatibilityEngine();
    private final com.contractguard.impact.ConsumerImpactAnalyzer impactAnalyzer =
            new com.contractguard.impact.ConsumerImpactAnalyzer();
    private final VersionPolicyAnalyzer versionPolicy = new VersionPolicyAnalyzer();
    private final ConsumerRegistry consumerRegistry;
    private final WebhookNotifier notifier;

    public PublicationService(RegistryStore store, SpecParser specParser,
                              ConsumerRegistry consumerRegistry, WebhookNotifier notifier) {
        this.store = store;
        this.specParser = specParser;
        this.consumerRegistry = consumerRegistry;
        this.notifier = notifier;
    }

    /** One evaluated old→new transition, before any persistence. */
    public record Evaluation(SpecDocument doc, String previousVersion,
                             List<CompatibilityFinding> findings, List<PolicyFinding> policyFindings,
                             com.contractguard.impact.ImpactSummary summary, DecisionResult decision) {
    }

    // ------------------------------------------------------------------ upload

    /**
     * Runs the full pipeline for one uploaded specification. Gate outcomes return the
     * attempt payload; invalid specs throw {@link SpecInvalidException} (HTTP 422, all
     * errors listed) and conflicts throw {@link ConflictException} (HTTP 409) - both
     * after persisting a rejected attempt and its audit row.
     *
     * @param notify false only for kit seeding (no webhook noise at startup)
     */
    public Map<String, Object> upload(String slug, String yaml, String filename, String actor,
                                      boolean notify) {
        RegistryStore.ServiceRow service = requireService(slug);
        String sha = sha256(yaml);
        String displayFilename = filename == null || filename.isBlank() ? "(body)" : filename;

        SpecDocument doc;
        try {
            doc = specParser.parse(yaml);
        } catch (SpecParseException e) {
            long attemptId = store.insertRejectedAttempt(service.id(), "(unknown)", sha, yaml,
                    errorText(e.getErrors()), actor,
                    new RegistryStore.AuditEntry("SPEC_REJECTED", "rejected", null,
                            "invalid specification (" + e.getErrors().size()
                                    + " error(s)); nothing stored"));
            throw new SpecInvalidException(e.getErrors(), attemptId);
        }
        String version = doc.getSpecVersion();

        // duplicate version: identical re-upload is an idempotent no-op, differing
        // content under the same version is a hard 409 (DECISIONS.md A-03)
        Optional<RegistryStore.SpecVersionRow> existing = store.findVersion(service.id(), version);
        if (existing.isPresent()) {
            if (existing.get().canonicalJson().equals(doc.getCanonicalJson())) {
                store.insertAuditEvent(slug, version, null, "SPEC_DUPLICATE_IDENTICAL", "no-op",
                        actor, null, "identical re-upload of registered version; nothing changed");
                Map<String, Object> out = baseResult(slug, version);
                out.put("status", "duplicate-identical");
                out.put("message", "this exact version is already registered (idempotent no-op)");
                return out;
            }
            long attemptId = store.insertRejectedAttempt(service.id(), version, sha, yaml,
                    "duplicate version with different content", actor,
                    new RegistryStore.AuditEntry("SPEC_REJECTED", "rejected", null,
                            "version " + version + " is already registered with different content"));
            throw new ConflictException("version " + version + " is already registered with "
                    + "different content (upload rejected; rejected uploads never reserve a version)");
        }

        // version regression (DECISIONS.md A-04)
        Optional<RegistryStore.SpecVersionRow> current = store.currentVersion(service.id());
        if (current.isPresent()) {
            Optional<Semver> oldSemver = Semver.parse(current.get().version());
            Optional<Semver> newSemver = Semver.parse(version);
            if (oldSemver.isPresent() && newSemver.isPresent()
                    && newSemver.get().compareTo(oldSemver.get()) < 0) {
                store.insertRejectedAttempt(service.id(), version, sha, yaml,
                        "version regression", actor,
                        new RegistryStore.AuditEntry("SPEC_REJECTED", "rejected", null,
                                "version regression: " + version + " < current "
                                        + current.get().version()));
                throw new ConflictException("version regression: " + version
                        + " is lower than the current published version " + current.get().version());
            }
        }

        Evaluation evaluation = evaluate(slug, current.orElse(null), doc);
        return gate(service, doc, evaluation, sha, displayFilename, yaml, actor, notify);
    }

    // ------------------------------------------------------------------ gate

    private Map<String, Object> gate(RegistryStore.ServiceRow service, SpecDocument doc,
                                     Evaluation evaluation, String sha, String filename,
                                     String yaml, String actor, boolean notify) {
        String version = doc.getSpecVersion();
        String effectiveJson = JSON.writeValueAsString(doc.getEffectiveSchemas());
        List<RegistryStore.FindingWithImpacts> prepared = prepareFindings(
                evaluation.findings(), evaluation.summary());
        List<String> affected = affectedConsumers(evaluation.summary());
        String decisionJson = writeDecisionJson(evaluation, affected);
        int breaking = count(evaluation.findings(), Severity.BREAKING);
        String details = "upload '" + filename + "' evaluated: " + breaking + " breaking, "
                + count(evaluation.findings(), Severity.WARN) + " warn, "
                + count(evaluation.findings(), Severity.INFO) + " info";
        switch (evaluation.decision().decision()) {
            case PUBLISH -> {
                long attemptId = store.autoPublishAttempt(service.id(), version, sha, filename,
                        yaml, doc.getCanonicalJson(), effectiveJson, actor, decisionJson,
                        prepared, actor,
                        new RegistryStore.AuditEntry("SPEC_UPLOADED", "auto_published", null,
                                details + " - published automatically (policy rule 1)"));
                if (notify) {
                    notifier.deliver("published", service.slug(), version,
                            webhookPayload("published", service.slug(), version, actor, null,
                                    evaluation.findings(), affected, "auto_published"));
                }
                return attemptDetail(attemptId);
            }
            case PENDING_ACK -> {
                long attemptId = store.insertGatedAttempt(service.id(), version, sha, yaml,
                        doc.getCanonicalJson(), "pending_ack", breaking,
                        evaluation.decision().requiredAction(), decisionJson, prepared, actor,
                        new RegistryStore.AuditEntry("SPEC_UPLOADED", "pending_ack", null,
                                details + " - acknowledgment required before publication"));
                return attemptDetail(attemptId);
            }
            default -> { // BLOCK
                long attemptId = store.insertGatedAttempt(service.id(), version, sha, yaml,
                        doc.getCanonicalJson(), "blocked", breaking,
                        evaluation.decision().requiredAction(), decisionJson, prepared, actor,
                        new RegistryStore.AuditEntry("SPEC_UPLOADED", "blocked", null,
                                details + " - blocked; affected consumers: "
                                        + String.join(", ", affected)));
                if (notify) {
                    notifier.deliver("blocked", service.slug(), version,
                            webhookPayload("blocked", service.slug(), version, actor, null,
                                    evaluation.findings(), affected, "blocked"));
                }
                return attemptDetail(attemptId);
            }
        }
    }

    // ------------------------------------------------------------------ dry-run check

    /** Evaluates a candidate without publishing anything (audit event only). */
    public Map<String, Object> check(String slug, String yaml, String filename, String actor) {
        RegistryStore.ServiceRow service = requireService(slug);
        String displayFilename = filename == null || filename.isBlank() ? "(body)" : filename;
        SpecDocument doc;
        try {
            doc = specParser.parse(yaml);
        } catch (SpecParseException e) {
            store.insertAuditEvent(slug, "(unknown)", null, "COMPATIBILITY_CHECK", "rejected",
                    actor, null, "dry-run check of '" + displayFilename + "' rejected: invalid specification");
            throw new SpecInvalidException(e.getErrors(), null);
        }
        Optional<RegistryStore.SpecVersionRow> current = store.currentVersion(service.id());
        Evaluation evaluation = evaluate(slug, current.orElse(null), doc);
        store.insertAuditEvent(slug, doc.getSpecVersion(), null, "COMPATIBILITY_CHECK",
                evaluation.decision().decision().name(), actor, null,
                "dry-run check of '" + displayFilename + "' against "
                        + (current.map(RegistryStore.SpecVersionRow::version).orElse("no stored version"))
                        + ": " + count(evaluation.findings(), Severity.BREAKING) + " breaking finding(s)");
        return report(slug, current.map(RegistryStore.SpecVersionRow::version).orElse(null),
                doc.getSpecVersion(), evaluation, true);
    }

    /** Dry-run comparison of two REGISTERED versions; publishes nothing. */
    public Map<String, Object> compare(String slug, String fromVersion, String toVersion, String actor) {
        RegistryStore.ServiceRow service = requireService(slug);
        RegistryStore.SpecVersionRow from = store.findVersion(service.id(), fromVersion)
                .orElseThrow(() -> new NotFoundException("version '" + fromVersion
                        + "' of '" + slug + "' is not registered"));
        RegistryStore.SpecVersionRow to = store.findVersion(service.id(), toVersion)
                .orElseThrow(() -> new NotFoundException("version '" + toVersion
                        + "' of '" + slug + "' is not registered"));
        SpecDocument oldDoc = specParser.parse(from.rawYaml());
        SpecDocument newDoc = specParser.parse(to.rawYaml());
        List<CompatibilityFinding> findings = engine.diff(oldDoc.getEffectiveSchemas(),
                newDoc.getEffectiveSchemas(), oldDoc.getPathAliases(), newDoc.getPathAliases());
        findings = appendLifecycleFindings(findings, oldDoc, newDoc);
        com.contractguard.impact.ImpactSummary summary = impactAnalyzer.analyze(slug, oldDoc,
                newDoc, findings, consumerRegistry.all());
        List<PolicyFinding> policyFindings = versionPolicy.analyze(fromVersion, toVersion,
                count(findings, Severity.BREAKING) > 0);
        DecisionResult decision = PublishingDecision.evaluate(findings, summary);
        store.insertAuditEvent(slug, toVersion, null, "COMPATIBILITY_COMPARE", decision.decision().name(),
                actor, null, "compared registered versions " + fromVersion + " -> " + toVersion + ": "
                        + count(findings, Severity.BREAKING) + " breaking finding(s)");
        return report(slug, fromVersion, toVersion, new Evaluation(newDoc, fromVersion, findings,
                policyFindings, summary, decision), true);
    }

    // ------------------------------------------------------------------ decision actions

    public Map<String, Object> acknowledge(long attemptId, String actor, String comment) {
        RegistryStore.AttemptRow attempt = requireAttempt(attemptId);
        if (!"pending_ack".equals(attempt.state())) {
            throw new InvalidRequestException("acknowledge is only valid from state "
                    + "'pending_ack' (current state: '" + attempt.state() + "')");
        }
        String details = "breaking changes acknowledged"
                + (comment == null || comment.isBlank() ? "" : "; comment: " + comment);
        return decide(attempt, "published", actor, comment, "ACKNOWLEDGED", details, true);
    }

    public Map<String, Object> override(long attemptId, String actor, String justification) {
        RegistryStore.AttemptRow attempt = requireAttempt(attemptId);
        if (!"blocked".equals(attempt.state())) {
            throw new InvalidRequestException("override is only valid from state "
                    + "'blocked' (current state: '" + attempt.state() + "')");
        }
        if (justification == null || justification.isBlank()) {
            throw new ValidationException("a non-empty justification is required to "
                    + "override a blocked publication");
        }
        String details = "blocked publication overridden; justification: " + justification;
        return decide(attempt, "overridden_published", actor, justification, "OVERRIDE",
                details, true);
    }

    public Map<String, Object> abandon(long attemptId, String actor) {
        RegistryStore.AttemptRow attempt = requireAttempt(attemptId);
        if (!"pending_ack".equals(attempt.state()) && !"blocked".equals(attempt.state())) {
            throw new InvalidRequestException("abandon is only valid from states "
                    + "'pending_ack' or 'blocked' (current state: '" + attempt.state() + "')");
        }
        return decide(attempt, "abandoned", actor, null, "ABANDONED",
                "publication attempt abandoned", false);
    }

    private Map<String, Object> decide(RegistryStore.AttemptRow attempt, String newState,
                                       String actor, String justification, String auditAction,
                                       String auditDetails, boolean notify) {
        String slug = store.serviceSlug(attempt.serviceId()).orElse("(unknown)");
        // the attempt was validated at upload time; re-parse is guaranteed to succeed
        SpecDocument doc = specParser.parse(attempt.rawYaml());
        String version = doc.getSpecVersion();
        // only publish outcomes make the version current; abandon/reject never store one
        boolean publishOutcome = "published".equals(newState) || "overridden_published".equals(newState);
        store.decideAndPublish(attempt.id(), attempt.serviceId(), slug, version, attempt.state(),
                attempt.sourceSha(), null, attempt.rawYaml(),
                publishOutcome ? doc.getCanonicalJson() : null,
                publishOutcome ? JSON.writeValueAsString(doc.getEffectiveSchemas()) : null,
                newState, actor, justification, auditAction,
                auditDetails + " (version " + version + ")");
        if (notify) {
            List<String> affected = store.affectedConsumers(attempt.id());
            notifier.deliver(newState, slug, version, webhookPayload(newState, slug, version,
                    actor, justification, storedFindings(attempt.id()), affected, newState));
        }
        return attemptDetail(attempt.id());
    }

    // ------------------------------------------------------------------ evaluation core

    private Evaluation evaluate(String slug, RegistryStore.SpecVersionRow current, SpecDocument newDoc) {
        List<CompatibilityFinding> findings;
        SpecDocument oldDoc;
        if (current == null) {
            oldDoc = newDoc;
            findings = List.of();
        } else {
            oldDoc = specParser.parse(current.rawYaml());
            findings = new ArrayList<>(engine.diff(oldDoc.getEffectiveSchemas(),
                    newDoc.getEffectiveSchemas(), oldDoc.getPathAliases(), newDoc.getPathAliases()));
            findings = appendLifecycleFindings(findings, oldDoc, newDoc);
        }
        com.contractguard.impact.ImpactSummary summary = impactAnalyzer.analyze(slug, oldDoc,
                newDoc, findings, consumerRegistry.all());
        String previousVersion = current == null ? null : current.version();
        List<PolicyFinding> policyFindings = versionPolicy.analyze(previousVersion,
                newDoc.getSpecVersion(), count(findings, Severity.BREAKING) > 0);
        DecisionResult decision = PublishingDecision.evaluate(findings, summary);
        return new Evaluation(newDoc, previousVersion, findings, policyFindings, summary, decision);
    }

    /**
     * Deprecation lifecycle findings (PLAN.md §6): a newly {@code deprecated} field is a
     * warn (non-gating); an {@code x-sunset} date in the past/future while the field is
     * still documented is a warn/info. These never gate (severity never BREAKING).
     */
    private List<CompatibilityFinding> appendLifecycleFindings(List<CompatibilityFinding> findings,
                                                               SpecDocument oldDoc, SpecDocument newDoc) {
        Set<String> previouslyDeprecated = new TreeSet<>();
        for (DeprecationRecord record : oldDoc.getDeprecations()) {
            previouslyDeprecated.add(record.humanPath());
        }
        LocalDate today = LocalDate.now();
        for (DeprecationRecord record : newDoc.getDeprecations()) {
            String where = record.humanPath();
            if (!previouslyDeprecated.contains(where)) {
                findings.add(new CompatibilityFinding("DEPRECATION.ADDED", Severity.WARN, "LIFECYCLE",
                        null, null, where, where,
                        "field newly marked deprecated"
                                + record.sunsetRaw().map(s -> " (x-sunset: " + s + ")").orElse(""),
                        null, null));
            }
            record.sunset().ifPresent(date -> {
                String code = date.isAfter(today) ? "SUNSET_SCHEDULED" : "SUNSET_PASSED";
                Severity severity = date.isAfter(today) ? Severity.INFO : Severity.WARN;
                String detail = "x-sunset date " + date + (date.isAfter(today)
                        ? " is scheduled"
                        : " has passed while the field is still documented");
                findings.add(new CompatibilityFinding(code, severity, "LIFECYCLE", null, null,
                        where, where, detail, null, null));
            });
        }
        return findings;
    }

    // ------------------------------------------------------------------ views (API payloads)

    /** Full persisted attempt: state, findings with impacts, decision and allowed actions. */
    public Map<String, Object> attemptDetail(long attemptId) {
        RegistryStore.AttemptRow attempt = requireAttempt(attemptId);
        String slug = store.serviceSlug(attempt.serviceId()).orElse("(unknown)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", attempt.id());
        out.put("service", slug);
        out.put("incomingVersion", attempt.incomingVersion());
        out.put("state", attempt.state());
        out.put("breakingCount", attempt.breakingCount());
        out.put("requiredAction", attempt.requiredAction());
        out.put("createdAt", attempt.createdAt() == null ? null : attempt.createdAt().toString());
        out.put("decidedAt", attempt.decidedAt() == null ? null : attempt.decidedAt().toString());
        out.put("decidedBy", attempt.decidedBy());
        out.put("justification", attempt.justification());
        out.put("note", attempt.note());
        out.put("allowedActions", allowedActions(attempt.state()));

        JsonNode decision = parseDecision(attempt.decisionJson());
        if (decision != null) {
            out.put("decision", textOrNull(decision, "decision"));
            out.put("reasons", stringList(decision, "reasons"));
            out.put("previousVersion", textOrNull(decision, "previousVersion"));
            out.put("policyFindings", findingListOf(decision, "policyFindings"));
            out.put("affectedConsumers", stringList(decision, "affectedConsumers"));
        }

        List<RegistryStore.StoredFinding> findings = store.findingsForAttempt(attempt.id());
        Map<Long, List<RegistryStore.StoredImpact>> impactsByFinding = new LinkedHashMap<>();
        for (RegistryStore.StoredImpact impact : store.impactsForAttempt(attempt.id())) {
            impactsByFinding.computeIfAbsent(impact.findingId(), k -> new ArrayList<>()).add(impact);
        }
        List<Map<String, Object>> findingViews = new ArrayList<>();
        for (RegistryStore.StoredFinding finding : findings) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", finding.code());
            view.put("severity", finding.severity());
            view.put("scope", finding.scope());
            view.put("method", finding.method());
            view.put("path", finding.path());
            view.put("location", finding.location());
            view.put("fieldPath", finding.fieldPath());
            view.put("detail", finding.detail());
            List<Map<String, Object>> impactViews = new ArrayList<>();
            for (RegistryStore.StoredImpact impact : impactsByFinding.getOrDefault(finding.id(), List.of())) {
                impactViews.add(Map.of("consumer", impact.consumer(), "level",
                        impact.level().toUpperCase(),
                        "reason", impact.reason() == null ? "" : impact.reason()));
            }
            view.put("impacts", impactViews);
            if (finding.oldJson() != null) {
                view.put("oldJson", finding.oldJson());
            }
            if (finding.newJson() != null) {
                view.put("newJson", finding.newJson());
            }
            findingViews.add(view);
        }
        out.put("findings", findingViews);
        return out;
    }

    /** Dry-run report payload (check + compare + upload gate reuse the same shape). */
    private Map<String, Object> report(String slug, String fromVersion, String toVersion,
                                       Evaluation evaluation, boolean dryRun) {
        Map<String, Object> out = baseResult(slug, toVersion);
        out.put("oldVersion", fromVersion);
        out.put("decision", evaluation.decision().decision().name());
        out.put("requiredAction", evaluation.decision().requiredAction());
        out.put("reasons", evaluation.decision().reasons());
        out.put("policyFindings", evaluation.policyFindings().stream()
                .map(p -> Map.of("code", p.code(), "severity", p.severity().code(),
                        "detail", p.detail()))
                .toList());
        out.put("findings", evaluation.findings().stream().map(PublicationService::findingView).toList());
        out.put("summary", summaryView(evaluation.summary()));
        if (dryRun) {
            out.put("note", "dry-run evaluation only - nothing was published or stored");
        }
        return out;
    }

    private static Map<String, Object> findingView(CompatibilityFinding f) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", f.code());
        view.put("severity", f.severity().code());
        view.put("scope", f.scope());
        view.put("method", f.method());
        view.put("path", f.path());
        view.put("location", f.location());
        view.put("fieldPath", f.fieldPath());
        view.put("detail", f.detail());
        return view;
    }

    private Map<String, Object> summaryView(com.contractguard.impact.ImpactSummary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("affectedCount", summary.affectedCount());
        out.put("possibleCount", summary.possibleCount());
        out.put("unaffectedCount", summary.unaffectedCount());
        List<Map<String, Object>> consumers = new ArrayList<>();
        for (var c : summary.consumers()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("consumer", c.consumer());
            view.put("team", c.team() == null ? "" : c.team());
            view.put("level", c.level().name());
            view.put("reason", c.reason() == null ? "" : c.reason());
            view.put("operations", c.operations());
            List<Map<String, Object>> impacts = new ArrayList<>();
            for (var i : c.impacts()) {
                Map<String, Object> impactView = new LinkedHashMap<>();
                impactView.put("findingCode", i.findingCode());
                impactView.put("findingSeverity", i.findingSeverity());
                impactView.put("level", i.level().name());
                impactView.put("location", i.location() == null ? "" : i.location());
                impactView.put("fieldPath", i.fieldPath() == null ? "" : i.fieldPath());
                impactView.put("reason", i.reason() == null ? "" : i.reason());
                impacts.add(impactView);
            }
            view.put("impacts", impacts);
            consumers.add(view);
        }
        out.put("consumers", consumers);
        return out;
    }

    private static Map<String, Object> baseResult(String slug, String version) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", slug);
        out.put("version", version);
        return out;
    }

    // ------------------------------------------------------------------ history / changelog

    public Map<String, Object> history(String slug) {
        RegistryStore.ServiceRow service = requireService(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", slug);
        out.put("currentVersion", store.currentVersion(service.id())
                .map(RegistryStore.SpecVersionRow::version).orElse(null));
        out.put("versions", store.listVersions(service.id()).stream().map(v -> Map.of(
                "version", v.version(), "state", v.state(), "sourceSha", v.sourceSha(),
                "sourceFilename", v.sourceFilename() == null ? "" : v.sourceFilename(),
                "publishedAt", v.publishedAt() == null ? "" : v.publishedAt().toString(),
                "publishedBy", v.publishedBy() == null ? "anonymous" : v.publishedBy())).toList());
        out.put("attempts", store.listAttempts(service.id()).stream().map(a -> {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("attemptId", a.id());
            view.put("incomingVersion", a.incomingVersion());
            view.put("state", a.state());
            view.put("breakingCount", a.breakingCount());
            view.put("createdAt", a.createdAt() == null ? null : a.createdAt().toString());
            view.put("decidedAt", a.decidedAt() == null ? null : a.decidedAt().toString());
            view.put("decidedBy", a.decidedBy());
            if (a.justification() != null) {
                view.put("justification", a.justification());
            }
            return view;
        }).toList());
        return out;
    }

    /** Changelog built from the actual stored findings of every published transition. */
    public Map<String, Object> changelog(String slug) {
        RegistryStore.ServiceRow service = requireService(slug);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RegistryStore.AttemptSummary attempt : store.listAttempts(service.id())) {
            if (!isPublished(attempt.state())) {
                continue;
            }
            RegistryStore.AttemptRow row = store.findAttempt(attempt.id()).orElse(null);
            if (row == null) {
                continue;
            }
            JsonNode decision = parseDecision(row.decisionJson());
            List<RegistryStore.StoredFinding> findings = store.findingsForAttempt(attempt.id());
            Map<Long, List<RegistryStore.StoredImpact>> impactsByFinding = new LinkedHashMap<>();
            for (RegistryStore.StoredImpact impact : store.impactsForAttempt(attempt.id())) {
                impactsByFinding.computeIfAbsent(impact.findingId(), k -> new ArrayList<>()).add(impact);
            }
            List<Map<String, Object>> changes = new ArrayList<>();
            for (RegistryStore.StoredFinding finding : findings) {
                Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("code", finding.code());
                view.put("severity", finding.severity());
                view.put("location", finding.location());
                view.put("fieldPath", finding.fieldPath());
                view.put("detail", finding.detail());
                List<String> consumers = new ArrayList<>();
                for (RegistryStore.StoredImpact impact : impactsByFinding.getOrDefault(finding.id(), List.of())) {
                    consumers.add(impact.consumer() + " (" + impact.level().toUpperCase() + ")");
                }
                if (!consumers.isEmpty()) {
                    view.put("consumerImpact", consumers);
                }
                changes.add(view);
            }
            List<String> affected = store.affectedConsumers(attempt.id());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", row.incomingVersion());
            entry.put("previousVersion", decision == null ? null : textOrNull(decision, "previousVersion"));
            entry.put("decision", row.state());
            entry.put("publishedAt", row.decidedAt() == null ? null : row.decidedAt().toString());
            entry.put("publishedBy", row.decidedBy());
            if ("overridden_published".equals(row.state())) {
                entry.put("justification", row.justification());
            }
            entry.put("summary", Map.of(
                    "breaking", countSeverity(findings, "breaking"),
                    "warn", countSeverity(findings, "warn"),
                    "info", countSeverity(findings, "info")));
            entry.put("compatibilityChanges", changes);
            entry.put("policyWarnings", decision == null ? List.of()
                    : findingListOf(decision, "policyFindings"));
            entry.put("affectedConsumers", affected);
            entries.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", slug);
        out.put("entries", entries);
        return out;
    }

    private static boolean isPublished(String state) {
        return "auto_published".equals(state) || "published".equals(state)
                || "overridden_published".equals(state);
    }

    private static int countSeverity(List<RegistryStore.StoredFinding> findings, String severity) {
        return (int) findings.stream().filter(f -> severity.equals(f.severity())).count();
    }

    // ------------------------------------------------------------------ helpers

    private RegistryStore.ServiceRow requireService(String slug) {
        return store.findService(slug)
                .orElseThrow(() -> new NotFoundException("service not found: " + slug));
    }

    private RegistryStore.AttemptRow requireAttempt(long attemptId) {
        return store.findAttempt(attemptId)
                .orElseThrow(() -> new NotFoundException("publication attempt not found: " + attemptId));
    }

    private static List<String> allowedActions(String state) {
        return switch (state) {
            case "pending_ack" -> List.of("acknowledge", "abandon");
            case "blocked" -> List.of("override", "abandon");
            default -> List.of();
        };
    }

    private static List<String> affectedConsumers(com.contractguard.impact.ImpactSummary summary) {
        return summary.consumers().stream()
                .filter(c -> c.level() == com.contractguard.impact.ImpactLevel.AFFECTED)
                .map(com.contractguard.impact.ConsumerImpact::consumer)
                .sorted()
                .toList();
    }

    /**
     * Pairs each finding with its per-consumer impact entries from the summary (matched
     * by code + method + location + fieldPath - the same triple the engine guarantees
     * unique per finding).
     */
    private List<RegistryStore.FindingWithImpacts> prepareFindings(
            List<CompatibilityFinding> findings, com.contractguard.impact.ImpactSummary summary) {
        List<RegistryStore.FindingWithImpacts> out = new ArrayList<>();
        for (CompatibilityFinding finding : findings) {
            List<RegistryStore.FindingWithImpacts.ImpactEntry> impacts = new ArrayList<>();
            for (var consumer : summary.consumers()) {
                for (var impact : consumer.impacts()) {
                    if (impact.findingCode().equals(finding.code())
                            && eq(impact.method(), finding.method())
                            && eq(impact.path(), finding.path())
                            && eq(impact.location(), finding.location())
                            && eq(impact.fieldPath(), finding.fieldPath())) {
                        impacts.add(new RegistryStore.FindingWithImpacts.ImpactEntry(
                                consumer.consumer(), impact.level().name().toLowerCase(),
                                impact.reason()));
                    }
                }
            }
            out.add(new RegistryStore.FindingWithImpacts(finding.code(), finding.severity().code(),
                    finding.scope(), finding.method(), finding.path(), finding.location(),
                    finding.fieldPath(), finding.detail(), finding.oldJson(), finding.newJson(),
                    impacts));
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String writeDecisionJson(Evaluation evaluation, List<String> affected) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", evaluation.decision().decision().name());
        out.put("requiredAction", evaluation.decision().requiredAction());
        out.put("reasons", evaluation.decision().reasons());
        out.put("previousVersion", evaluation.previousVersion());
        out.put("policyFindings", evaluation.policyFindings().stream()
                .map(p -> Map.of("code", p.code(), "severity", p.severity().code(), "detail", p.detail()))
                .toList());
        out.put("affectedConsumers", affected);
        out.put("evaluatedAt", java.time.Instant.now().toString());
        return JSON.writeValueAsString(out);
    }

    private String webhookPayload(String event, String slug, String version, String actor,
                                  String justification, List<CompatibilityFinding> findings,
                                  List<String> affected, String decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("service", slug);
        payload.put("version", version);
        payload.put("decision", decision);
        payload.put("actor", actor == null ? "anonymous" : actor);
        if (justification != null && !justification.isBlank()) {
            payload.put("justification", justification);
        }
        payload.put("affected_consumers", affected);
        payload.put("findings", findings.stream()
                .filter(f -> f.severity() == Severity.BREAKING || f.severity() == Severity.WARN)
                .limit(WEBHOOK_FINDING_LIMIT)
                .map(f -> Map.of("code", f.code(), "severity", f.severity().code(),
                        "location", f.location() == null ? "" : f.location(),
                        "field_path", f.fieldPath() == null ? "" : f.fieldPath()))
                .toList());
        payload.put("timestamp", java.time.Instant.now().toString());
        return JSON.writeValueAsString(payload);
    }

    private List<CompatibilityFinding> storedFindings(long attemptId) {
        return store.findingsForAttempt(attemptId).stream()
                .map(f -> new CompatibilityFinding(f.code(),
                        Severity.valueOf(f.severity().toUpperCase()), f.scope(), f.method(),
                        f.path(), f.location(), f.fieldPath(), f.detail(), null, null))
                .toList();
    }

    private static JsonNode parseDecision(String decisionJson) {
        if (decisionJson == null || decisionJson.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(decisionJson);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> out = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode element : value) {
                out.add(element.stringValue());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> findingListOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<Map<String, Object>> out = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode element : value) {
                Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("code", textOrNull(element, "code"));
                view.put("severity", textOrNull(element, "severity"));
                view.put("detail", textOrNull(element, "detail"));
                out.add(view);
            }
        }
        return out;
    }

    private static int count(List<CompatibilityFinding> findings, Severity severity) {
        return (int) findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static String errorText(List<SpecValidationError> errors) {
        return String.join("; ", errors.stream().map(SpecValidationError::toString).toList());
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
