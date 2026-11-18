/**
 * Consumer registry: reads consumer YAML definitions (Jackson 3), normalizes bindings
 * (method + normalized path shape so {id} ≡ {quoteId}), and matches bindings to spec
 * operations.
 *
 * Responsibility boundary: classifies no severity and performs no impact gating.
 */
package com.contractguard.consumers;
