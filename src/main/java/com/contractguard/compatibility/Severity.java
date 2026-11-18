package com.contractguard.compatibility;

/**
 * Finding severity. BREAKING findings drive the publishing gate; WARN and INFO
 * findings are displayed and audited but never gate publication by themselves.
 */
public enum Severity {
    BREAKING("breaking"),
    WARN("warn"),
    INFO("info");

    private final String code;

    Severity(String code) {
        this.code = code;
    }

    /** Lowercase code used in persistence and API payloads. */
    public String code() {
        return code;
    }
}
