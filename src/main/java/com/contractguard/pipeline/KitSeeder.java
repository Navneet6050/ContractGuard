package com.contractguard.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.contractguard.api.ApiException;
import com.contractguard.persistence.RegistryStore;

/**
 * Idempotent starter-kit import (DECISIONS.md A-06): at startup, every service folder
 * under the configured specs directory (default ./specs) is registered (if new) and its
 * YAML files are uploaded in FILENAME ORDER through the real publishing pipeline, with
 * webhooks suppressed. Whatever the pipeline decides (publish / pending_ack / blocked /
 * rejected) stands — the kit's broken file is legitimately rejected by the pipeline.
 *
 * A completed import leaves one KIT_IMPORT audit event per service; that event is the
 * idempotency marker: on restart, services that were already imported are skipped
 * entirely, so user uploads are never touched and the audit trail is not polluted.
 */
@Component
@ConditionalOnProperty(prefix = "contractguard.kit-import", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KitSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(KitSeeder.class);

    private final PublicationService publicationService;
    private final RegistryStore store;
    private final Path specsDir;

    public KitSeeder(PublicationService publicationService, RegistryStore store,
                     @org.springframework.beans.factory.annotation.Value(
                             "${contractguard.specs-dir:specs}") String specsDir) {
        this.publicationService = publicationService;
        this.store = store;
        this.specsDir = Path.of(specsDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isDirectory(specsDir)) {
            LOG.info("kit import skipped: specs directory '{}' not found", specsDir);
            return;
        }
        try (Stream<Path> services = Files.list(specsDir)) {
            List<Path> serviceDirs = services.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int imported = 0;
            for (Path serviceDir : serviceDirs) {
                String slug = serviceDir.getFileName().toString();
                if (store.hasAuditEvent(slug, "KIT_IMPORT")) {
                    continue; // already imported on a previous start: never touch user data
                }
                imported++;
                seedService(serviceDir, slug);
            }
            LOG.info("kit import: {} service(s) seeded from {}", imported, specsDir);
        } catch (IOException e) {
            LOG.error("kit import failed to read {}: {}", specsDir, e.getMessage(), e);
        }
    }

    private void seedService(Path serviceDir, String slug) {
        if (!store.serviceExists(slug)) {
            store.insertService(slug, slug, null, "starter kit demo service");
            store.insertAuditEvent(slug, null, null, "SERVICE_REGISTERED", "registered",
                    "kit-importer", null, "service registered during starter kit import");
        }
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        List<String> fileNotes = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.list(serviceDir)) {
            List<Path> yamlFiles = files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path file : yamlFiles) {
                String filename = file.getFileName().toString();
                String outcome = seedFile(slug, filename, Files.readString(file));
                outcomes.merge(outcome, 1, Integer::sum);
                fileNotes.add(filename + "=" + outcome);
            }
        } catch (IOException e) {
            fileNotes.add("read error: " + e.getMessage());
        }
        store.insertAuditEvent(slug, null, null, "KIT_IMPORT", "completed", "kit-importer", null,
                "starter kit import: " + fileNotes);
        LOG.info("kit import for {}: {}", slug, fileNotes);
    }

    /** Uploads one kit file through the real pipeline; any gate outcome (including a
     *  legitimate rejection) is counted, never propagated. */
    private String seedFile(String slug, String filename, String yaml) {
        try {
            Map<String, Object> result = publicationService.upload(slug, yaml, filename,
                    "kit-importer", false);
            Object state = result.get("state");
            return state == null ? String.valueOf(result.getOrDefault("status", "unknown"))
                    : String.valueOf(state);
        } catch (ApiException e) {
            return "rejected";
        } catch (RuntimeException e) {
            LOG.error("kit import: unexpected failure uploading {}/{}: {}", slug, filename,
                    e.getMessage(), e);
            return "failed";
        }
    }
}
