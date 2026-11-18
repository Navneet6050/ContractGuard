package com.contractguard.consumers;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry loads ALL declaration files from a directory eagerly and
 * deterministically. Verified against the real (read-only) kit directory.
 */
class ConsumerRegistryTest {

    @Test
    @DisplayName("kit directory: all four consumers load, names preserved, deterministic order")
    void loadsKitDirectory() {
        ConsumerRegistry registry = new ConsumerRegistry(Path.of("consumers"));
        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.all()).extracting(ConsumerDeclaration::name)
                .containsExactly("mobile-app", "notifications-svc", "partner-portal", "payments-svc");
        assertThat(registry.byName("payments-svc")).isPresent();
        assertThat(registry.byName("nope")).isEmpty();
    }

    @Test
    @DisplayName("a broken declaration file fails startup loudly, naming the file")
    void brokenFileFailsLoudly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("good.yaml"), """
                consumer: good
                uses: []
                """);
        Files.writeString(dir.resolve("broken.yaml"), """
                consumer: broken
                uses:
                - method: GET
                """);
        assertThatThrownBy(() -> new ConsumerRegistry(dir))
                .isInstanceOf(ConsumerParseException.class)
                .hasMessageContaining("broken.yaml")
                .hasMessageContaining("provider is required");
    }

    @Test
    @DisplayName("missing directory fails with a clear message")
    void missingDirectory() {
        assertThatThrownBy(() -> new ConsumerRegistry(Path.of("does-not-exist")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consumer directory not found");
    }
}
