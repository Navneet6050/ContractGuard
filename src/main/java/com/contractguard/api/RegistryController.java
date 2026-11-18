package com.contractguard.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.contractguard.persistence.RegistryStore;
import com.contractguard.pipeline.PublicationService;
import com.contractguard.specs.SpecParseException;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP surface for the contract registry and publishing workflow (PLAN.md §5): service
 * registration, spec upload with the publishing gate, dry-run check/compare, attempt
 * decisions (acknowledge / override / abandon) and the history/changelog/audit/
 * notification views.
 *
 * Every mutating endpoint attributes the actor from the optional {@code X-Acting-User}
 * header (default "anonymous"). That header is ATTRIBUTION for the audit trail only —
 * it is not authentication or authorization; this application intentionally implements
 * no RBAC (hackathon scope, DECISIONS.md A-07).
 */
@RestController
public class RegistryController {

    private final PublicationService publicationService;
    private final RegistryStore store;

    public RegistryController(PublicationService publicationService, RegistryStore store) {
        this.publicationService = publicationService;
        this.store = store;
    }

    // ------------------------------------------------------------------ services

    /** Request body of POST /api/services. */
    public record ServiceRequest(@NotBlank String slug, String title, String ownerTeam,
                                 String description) {
    }

    @PostMapping("/api/services")
    public Map<String, Object> registerService(@RequestBody ServiceRequest request,
                                               @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        String slug = request.slug() == null ? "" : request.slug().trim();
        if (slug.isEmpty()) {
            throw new InvalidRequestException("'slug' is required");
        }
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new InvalidRequestException("'slug' must be lowercase letters, digits and "
                    + "dashes (e.g. quote-api)");
        }
        if (store.serviceExists(slug)) {
            throw new ConflictException("service '" + slug + "' is already registered");
        }
        String title = request.title() == null || request.title().isBlank()
                ? slug : request.title().trim();
        String who = actorOrAnonymous(actor);
        long id = store.insertService(slug, title,
                request.ownerTeam() == null ? null : request.ownerTeam().trim(),
                request.description() == null ? null : request.description().trim());
        store.insertAuditEvent(slug, null, null, "SERVICE_REGISTERED", "registered", who, null,
                "service registered (title: " + title + ")");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("slug", slug);
        out.put("title", title);
        out.put("ownerTeam", request.ownerTeam());
        out.put("description", request.description());
        return out;
    }

    @GetMapping("/api/services")
    public List<RegistryStore.ServiceOverview> services() {
        return store.listServices();
    }

    @GetMapping("/api/services/{slug}")
    public Map<String, Object> service(@PathVariable String slug) {
        RegistryStore.ServiceRow service = requireService(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", service.slug());
        out.put("title", service.title());
        out.put("ownerTeam", service.ownerTeam());
        out.put("description", service.description());
        out.put("createdAt", service.createdAt() == null ? null : service.createdAt().toString());
        out.put("currentVersion", store.currentVersion(service.id())
                .map(RegistryStore.SpecVersionRow::version).orElse(null));
        out.put("versions", store.listVersions(service.id()));
        out.put("attempts", store.listAttempts(service.id()));
        return out;
    }

    @GetMapping("/api/services/{slug}/versions")
    public List<RegistryStore.VersionSummary> versions(@PathVariable String slug) {
        return store.listVersions(requireService(slug).id());
    }

    @GetMapping("/api/services/{slug}/versions/{version}")
    public Map<String, Object> version(@PathVariable String slug, @PathVariable String version) {
        RegistryStore.ServiceRow service = requireService(slug);
        RegistryStore.SpecVersionRow row = store.findVersion(service.id(), version)
                .orElseThrow(() -> new NotFoundException("version '" + version
                        + "' of '" + slug + "' is not registered"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", slug);
        out.put("version", row.version());
        out.put("state", row.state());
        out.put("sourceSha", row.sourceSha());
        out.put("sourceFilename", row.sourceFilename());
        out.put("publishedAt", row.publishedAt() == null ? null : row.publishedAt().toString());
        out.put("publishedBy", row.publishedBy());
        out.put("spec", row.rawYaml());
        return out;
    }

    // ------------------------------------------------------------------ upload / check / compare

    /** Request body of POST /api/services/{slug}/specs (raw spec text). */
    public record UploadRequest(@NotBlank String yaml, String filename) {
    }

    /**
     * Runs the full publishing pipeline for one candidate spec. Gate outcomes return
     * the attempt; invalid specs are rejected with 422 (nothing stored), conflicting
     * re-uploads and regressions with 409.
     */
    @PostMapping("/api/services/{slug}/specs")
    public Map<String, Object> upload(@PathVariable String slug,
                                      @RequestBody UploadRequest request,
                                      @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        if (request.yaml() == null || request.yaml().isBlank()) {
            throw new InvalidRequestException("'yaml' is required");
        }
        return publicationService.upload(slug, request.yaml(), request.filename(),
                actorOrAnonymous(actor), true);
    }

    /** Request body of POST /api/services/{slug}/check. */
    public record CheckRequest(@NotBlank String yaml, String filename) {
    }

    /** Dry-run: full decision report for a candidate version; publishes nothing. */
    @PostMapping("/api/services/{slug}/check")
    public Map<String, Object> check(@PathVariable String slug,
                                     @RequestBody CheckRequest request,
                                     @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        if (request.yaml() == null || request.yaml().isBlank()) {
            throw new InvalidRequestException("'yaml' is required");
        }
        return publicationService.check(slug, request.yaml(), request.filename(),
                actorOrAnonymous(actor));
    }

    /** Dry-run comparison of two REGISTERED versions; publishes nothing. */
    @GetMapping("/api/services/{slug}/compare")
    public Map<String, Object> compare(@PathVariable String slug,
                                       @RequestParam String from,
                                       @RequestParam String to,
                                       @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        return publicationService.compare(slug, from, to, actorOrAnonymous(actor));
    }

    // ------------------------------------------------------------------ attempts

    @GetMapping("/api/services/{slug}/attempts")
    public List<RegistryStore.AttemptSummary> attempts(@PathVariable String slug) {
        return store.listAttempts(requireService(slug).id());
    }

    @GetMapping("/api/attempts/{id}")
    public Map<String, Object> attempt(@PathVariable long id) {
        return publicationService.attemptDetail(id);
    }

    /** Request body of POST /api/attempts/{id}/acknowledge. */
    public record AcknowledgeRequest(String comment) {
    }

    /** Explicit acknowledgment of breaking changes; only valid from pending_ack. */
    @PostMapping("/api/attempts/{id}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable long id,
                                           @RequestBody(required = false) AcknowledgeRequest body,
                                           @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        return publicationService.acknowledge(id, actorOrAnonymous(actor),
                body == null ? null : body.comment());
    }

    /** Request body of POST /api/attempts/{id}/override. */
    public record OverrideRequest(String justification) {
    }

    /** Override of a blocked publication; requires a non-empty justification. */
    @PostMapping("/api/attempts/{id}/override")
    public Map<String, Object> override(@PathVariable long id,
                                        @RequestBody(required = false) OverrideRequest body,
                                        @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        return publicationService.override(id, actorOrAnonymous(actor),
                body == null ? null : body.justification());
    }

    /** Withdraw a pending_ack or blocked attempt. */
    @PostMapping("/api/attempts/{id}/abandon")
    public Map<String, Object> abandon(@PathVariable long id,
                                       @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        return publicationService.abandon(id, actorOrAnonymous(actor));
    }

    // ------------------------------------------------------------------ history / changelog / audit / notifications

    @GetMapping("/api/services/{slug}/history")
    public Map<String, Object> history(@PathVariable String slug) {
        return publicationService.history(slug);
    }

    @GetMapping("/api/services/{slug}/changelog")
    public Map<String, Object> changelog(@PathVariable String slug) {
        return publicationService.changelog(slug);
    }

    @GetMapping("/api/audit")
    public List<RegistryStore.AuditRow> audit(@RequestParam(required = false) String service,
                                              @RequestParam(required = false) Long attempt) {
        return store.listAudit(service, attempt);
    }

    @GetMapping("/api/notifications")
    public List<RegistryStore.NotificationRow> notifications() {
        return store.listNotifications();
    }

    // ------------------------------------------------------------------ helpers

    private RegistryStore.ServiceRow requireService(String slug) {
        return store.findService(slug)
                .orElseThrow(() -> new NotFoundException("service not found: " + slug));
    }

    private static String actorOrAnonymous(String actor) {
        return actor == null || actor.isBlank() ? "anonymous" : actor.trim();
    }

    // ------------------------------------------------------------------ error contract

    /** Workflow errors (404/409/400): {"error": message}. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /**
     * Invalid uploaded spec (422): every collected validation error is listed — policy
     * rule 4, rejection is unconditional and nothing is stored.
     */
    @ExceptionHandler(SpecInvalidException.class)
    public ResponseEntity<Map<String, Object>> invalidSpec(SpecInvalidException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("errors", e.errors().stream()
                .map(err -> Map.of("pointer", err.pointer(), "message", err.message()))
                .toList());
        if (e.attemptId() != null) {
            body.put("attemptId", e.attemptId());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** Safety net: any parse failure escaping the pipeline maps to 422 with the diagnosis. */
    @ExceptionHandler(SpecParseException.class)
    public ResponseEntity<Map<String, String>> unhandledParse(SpecParseException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", e.getMessage()));
    }

    /** Malformed request body (broken JSON): same error contract as invalid specs. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "malformed request body: " + e.getMostSpecificCause().getMessage()));
    }
}
