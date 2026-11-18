package com.contractguard.specs;

/**
 * A wire location: HTTP method + normalized path shape + one of the scopes
 * {@code requestBody}, {@code param:<in>:<ordinal>} or {@code response:<status>}.
 * Two specifications with equal effective-schema maps for all locations have equal
 * wire contracts, regardless of component naming or internal $ref layout.
 */
public record EffectiveLocation(String method, String pathShape, String scope) {

    public String key() {
        return method + " " + pathShape + " :: " + scope;
    }
}
