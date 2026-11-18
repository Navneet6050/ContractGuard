package com.contractguard.consumers;

import java.util.List;

/**
 * A registered consumer: identity (name, team) plus every operation it declares
 * against any provider service. The consumer name and the provider identifiers are
 * the original strings from the YAML file — they are the join keys to the rest of
 * the system (service slugs).
 */
public record ConsumerDeclaration(String name, String team, List<DeclaredOperation> operations) {

    public ConsumerDeclaration {
        operations = List.copyOf(operations);
    }

    /** Declared bindings against one provider service, in file order. */
    public List<DeclaredOperation> forProvider(String service) {
        return operations.stream().filter(op -> op.provider().equals(service)).toList();
    }
}
