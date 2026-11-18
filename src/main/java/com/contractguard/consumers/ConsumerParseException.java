package com.contractguard.consumers;

/**
 * Thrown when a consumer declaration file is structurally invalid. Mirrors the
 * collect-all style of the specs module: the message lists every problem found.
 */
public class ConsumerParseException extends RuntimeException {

    public ConsumerParseException(String source, java.util.List<String> errors) {
        super("consumer declaration" + (source == null || source.isBlank() ? "" : " " + source)
                + " invalid: " + String.join("; ", errors));
    }
}
