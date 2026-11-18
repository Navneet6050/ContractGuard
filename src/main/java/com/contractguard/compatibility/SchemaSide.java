package com.contractguard.compatibility;

/**
 * Which side of the wire a schema sits on. Drives the asymmetry semantics of
 * PLAN.md §6: on the REQUEST side the accepted value space may only grow
 * (narrowing is breaking); on the RESPONSE side the guaranteed value space may
 * only shrink in promise (widening / weaker guarantees are breaking).
 */
public enum SchemaSide {
    REQUEST("REQ"),
    RESPONSE("RESP");

    private final String prefix;

    SchemaSide(String prefix) {
        this.prefix = prefix;
    }

    /** Rule-code prefix for this side ("REQ" / "RESP"). */
    public String prefix() {
        return prefix;
    }
}
