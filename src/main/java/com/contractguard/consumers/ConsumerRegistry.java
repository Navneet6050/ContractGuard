package com.contractguard.consumers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registry of consumer declarations, loaded from YAML files in a configured directory
 * (default {@code ./consumers} — the read-only starter-kit folder in development).
 * All files are parsed eagerly: an invalid declaration fails startup loudly instead of
 * silently shrinking the impact matrix. File order is normalized (sorted by file name)
 * so the registry is deterministic.
 */
@Component
public class ConsumerRegistry {

    private final List<ConsumerDeclaration> consumers;

    /** Spring constructor: directory from configuration (default: the kit's ./consumers). */
    @org.springframework.beans.factory.annotation.Autowired
    public ConsumerRegistry(@Value("${contractguard.consumers-dir:consumers}") String directory) {
        this.consumers = load(Path.of(directory));
    }

    /** Test/programmatic constructor: loads from an explicit directory (not for Spring). */
    public ConsumerRegistry(Path directory) {
        this.consumers = load(directory);
    }

    private static List<ConsumerDeclaration> load(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("consumer directory not found: "
                    + directory.toAbsolutePath() + " — set contractguard.consumers-dir");
        }
        ConsumerParser parser = new ConsumerParser();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> yamlFiles = files
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (yamlFiles.isEmpty()) {
                throw new IllegalStateException("no consumer declaration files in " + directory.toAbsolutePath());
            }
            return yamlFiles.stream()
                    .map(file -> {
                        try {
                            return parser.parse(Files.readString(file), file.getFileName().toString());
                        } catch (IOException e) {
                            throw new IllegalStateException("cannot read consumer file " + file, e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list consumer directory " + directory.toAbsolutePath(), e);
        }
    }

    /** All registered consumers, in deterministic (file-name) order. */
    public List<ConsumerDeclaration> all() {
        return consumers;
    }

    public Optional<ConsumerDeclaration> byName(String name) {
        return consumers.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public int size() {
        return consumers.size();
    }
}
