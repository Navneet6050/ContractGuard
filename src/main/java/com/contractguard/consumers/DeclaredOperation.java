package com.contractguard.consumers;

import java.util.List;

/**
 * One declared binding of a consumer against one provider operation. Identity is
 * preserved verbatim ({@code provider}, {@code method}, {@code rawPath}); matching
 * uses {@code pathShape} — the placeholder-name-insensitive normalization from the
 * specs module ({@code /quotes/{id}} ≡ {@code /quotes/{quoteId}} → {@code /quotes/{0}}).
 *
 * {@code sends} and {@code reads} keep the file's original strings: request-side
 * field paths AND parameter names (the format does not separate them), and response
 * field paths with dot nesting and {@code []} array items.
 */
public record DeclaredOperation(String provider, String method, String rawPath,
                                String pathShape, List<String> sends, List<String> reads) {

    public DeclaredOperation {
        sends = List.copyOf(sends);
        reads = List.copyOf(reads);
    }

    /** Display form, e.g. {@code GET /quotes/{id}}. */
    public String display() {
        return method + " " + rawPath;
    }
}
