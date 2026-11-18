package com.contractguard.specs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test helper: reads the read-only starter-kit fixtures from the repository root
 * (Maven surefire runs with the module directory as the working directory). Fixtures
 * are never modified by tests.
 */
final class Fixtures {

    private Fixtures() {
    }

    static String spec(String service, String file) {
        return read(Path.of("specs", service, file));
    }

    static String consumer(String file) {
        return read(Path.of("consumers", file));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + path + " — run tests from the repository root", e);
        }
    }
}
