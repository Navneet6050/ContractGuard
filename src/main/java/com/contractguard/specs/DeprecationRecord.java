package com.contractguard.specs;

import java.util.Optional;

/**
 * A field or operation found with {@code deprecated: true}, plus its optional
 * {@code x-sunset} date. Scanning is generic (any node with {@code deprecated});
 * classification into findings happens later in the pipeline.
 */
public record DeprecationRecord(String pointer, String humanPath, Optional<String> sunsetRaw,
                                Optional<java.time.LocalDate> sunset) {
}
