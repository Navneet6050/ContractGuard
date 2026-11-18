package com.contractguard.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.LocationKeys;
import com.contractguard.compatibility.Severity;
import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.DeclaredOperation;
import com.contractguard.specs.SpecDocument;

import tools.jackson.databind.JsonNode;

/**
 * Cumulative consumer-drift scan — the explicitly defined default for the
 * "which older versions are consumers still using?" question (DECISIONS.md A-08,
 * README consumer-format section).
 *
 * <p>Starter-kit consumer declarations carry NO version pinning — only
 * {@code provider, method, path, sends, reads}. They are point-in-time snapshots that
 * stay active until re-uploaded, while specs keep publishing ("the specs may have moved
 * on since"). The delta-based impact analysis only sees the current→candidate hop, so a
 * field removed in an ALREADY PUBLISHED version silently stops being reported on every
 * later upload even though the consumer's declaration was never updated.
 *
 * <p>This scan closes that gap WITHOUT inventing version pinning: for every declared
 * read/send of every consumer bound to this service it verifies the declared field still
 * exists in the INCOMING spec (across all its published evolution), and that the declared
 * operation still matches something. Missing usage is reported as a {@code warn}
 * finding (non-gating) with POSSIBLE-tier impacts naming the consumer — advisory,
 * because the drift may be accepted history (e.g. an already-overridden rename) rather
 * than damage caused by this candidate.
 *
 * <p>Findings use scope {@code consumers:reads|sends|binding} with locations in the
 * engine's {@code "VERB pathShape :: scope"} format so the standard impact join
 * (service + method + path shape) applies unchanged.
 */
public class ConsumerDriftScanner {

    /** Operation key ("VERB pathShape") → its effective-schema scope keys. */
    private record OperationScopes(String method, String shape, String aliasPath,
                                   List<String> scopeKeys) {
    }

    /**
     * @param service   provider service slug the candidate spec belongs to
     * @param oldDoc    the spec the candidate is diffed against (null for a first
     *                  publication); used ONLY to suppress findings already covered by
     *                  the current→candidate delta (e.g. OPERATION.REMOVED)
     * @param newDoc    the incoming (candidate or compared-to) spec
     * @param consumers all registered consumer declarations
     */
    public List<CompatibilityFinding> scan(String service, SpecDocument oldDoc, SpecDocument newDoc,
                                           List<ConsumerDeclaration> consumers) {
        return scan(service, oldDoc, newDoc, consumers, List.of());
    }

    /**
     * Full form: {@code deltaFindings} are the current→candidate compatibility findings
     * already computed for this hop. Drift they already explain (same operation and an
     * overlapping field — e.g. a removal or rename happening in THIS hop) is suppressed:
     * the delta engine owns this-hop damage; the drift scan reports only usage that
     * disappeared in EARLIER published versions.
     *
     * @param service   provider service slug the candidate spec belongs to
     * @param oldDoc    the spec the candidate is diffed against (null for a first
     *                  publication)
     * @param newDoc    the incoming (candidate or compared-to) spec
     * @param consumers all registered consumer declarations
     * @param deltaFindings findings already computed for the old→new hop (may be empty)
     */
    public List<CompatibilityFinding> scan(String service, SpecDocument oldDoc, SpecDocument newDoc,
                                           List<ConsumerDeclaration> consumers,
                                           List<CompatibilityFinding> deltaFindings) {
        Map<String, OperationScopes> newOps = operationsOf(newDoc);
        Map<String, OperationScopes> oldOps = oldDoc == null ? Map.of() : operationsOf(oldDoc);

        // one finding per (operation, kind, field) aggregated over consumers; stale
        // bindings aggregate per declared operation
        Map<String, Accumulated> drift = new TreeMap<>();
        for (ConsumerDeclaration consumer : consumers.stream()
                .sorted(Comparator.comparing(ConsumerDeclaration::name)).toList()) {
            for (DeclaredOperation binding : consumer.forProvider(service)) {
                OperationScopes op = newOps.get(opKey(binding.method(), binding.pathShape()));
                if (op == null) {
                    if (oldOps.containsKey(opKey(binding.method(), binding.pathShape()))) {
                        continue; // operation disappears in THIS hop: the delta engine
                                  // already reports OPERATION.REMOVED / PATH_SHAPE.CHANGED
                    }
                    accumulate(drift, binding, null, "binding", List.of(consumer.name()));
                    continue;
                }
                checkReads(drift, consumer, binding, op, newDoc);
                checkSends(drift, consumer, binding, op, newDoc);
            }
        }

        List<CompatibilityFinding> out = new ArrayList<>();
        for (Accumulated acc : drift.values()) {
            if (acc.field != null && coveredByDelta(acc, deltaFindings)) {
                continue; // this hop's delta finding already names the damage
            }
            String location = acc.method + " " + acc.shape + " :: consumers:" + acc.kind();
            out.add(new CompatibilityFinding(
                    acc.kind.equals("binding") ? "CONSUMER.STALE_BINDING" : "CONSUMER.DRIFT",
                    Severity.WARN, "CONSUMERS", acc.method, acc.aliasPath, location, acc.field,
                    acc.detail() + " (declared by: " + String.join(", ", acc.consumers) + ")",
                    null, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ field checks

    private void checkReads(Map<String, Accumulated> drift, ConsumerDeclaration consumer,
                            DeclaredOperation binding, OperationScopes op, SpecDocument newDoc) {
        if (binding.reads().isEmpty()) {
            return; // nothing declared: dependence cannot be claimed (PLAN.md §7)
        }
        List<JsonNode> responseSchemas = op.scopeKeys().stream()
                .filter(scope -> scope.startsWith("response:"))
                .map(scope -> parseSchema(newDoc.getEffectiveSchemas()
                        .get(op.method + " " + op.shape + " :: " + scope)))
                .filter(node -> node != null && !node.isNull())
                .toList();
        for (String read : sorted(binding.reads())) {
            boolean present = responseSchemas.stream().anyMatch(schema -> fieldExists(schema, read));
            if (!present) {
                accumulate(drift, binding, read, "reads", List.of(consumer.name()));
            }
        }
    }

    private void checkSends(Map<String, Accumulated> drift, ConsumerDeclaration consumer,
                            DeclaredOperation binding, OperationScopes op, SpecDocument newDoc) {
        if (binding.sends().isEmpty()) {
            return;
        }
        List<String> paramNames = op.scopeKeys().stream()
                .filter(scope -> scope.startsWith("param:"))
                .map(scope -> newDoc.getParamNames().get(op.method + " " + op.shape + " :: " + scope))
                .filter(name -> name != null)
                .toList();
        List<JsonNode> bodySchemas = op.scopeKeys().stream()
                .filter(scope -> scope.startsWith("requestBody"))
                .map(scope -> parseSchema(newDoc.getEffectiveSchemas()
                        .get(op.method + " " + op.shape + " :: " + scope)))
                .filter(node -> node != null && !node.isNull())
                .toList();
        for (String send : sorted(binding.sends())) {
            if (isPathParameter(binding, send) || paramNames.contains(send)) {
                continue; // parameter still accepted (or positional path placeholder)
            }
            boolean present = bodySchemas.stream().anyMatch(schema -> fieldExists(schema, send));
            if (!present) {
                accumulate(drift, binding, send, "sends", List.of(consumer.name()));
            }
        }
    }

    /**
     * Path-template placeholders are positional on the wire ({@code /quotes/{id}} ≡
     * {@code /quotes/{quoteId}}), so a declared send naming one cannot drift — the
     * binding's path shape still matching IS the existence proof.
     */
    private static boolean isPathParameter(DeclaredOperation binding, String send) {
        return send != null && !send.contains(".")
                && binding.rawPath().contains("{" + send + "}");
    }

    // ------------------------------------------------------------------ schema walk

    /**
     * True when the declared field path (dots for nesting, {@code []} array items) still
     * exists in the effective schema. Structural walk only — property presence, not
     * types or constraints.
     */
    static boolean fieldExists(JsonNode schema, String fieldPath) {
        JsonNode node = schema;
        for (String segment : segments(fieldPath)) {
            node = descend(node, segment);
            if (node == null) {
                return false;
            }
        }
        return true;
    }

    /** One segment hop: through {@code properties.<name>} and into array {@code items}. */
    private static JsonNode descend(JsonNode node, String segment) {
        while (node != null) {
            if (node.isObject()) {
                JsonNode properties = node.get("properties");
                JsonNode next = properties == null ? null : properties.get(segment);
                if (next != null) {
                    return next;
                }
                if (properties != null) {
                    // a property container without the segment: the field is absent
                    return null;
                }
                // {"type":"array","items":...} is also an object: follow the items
                JsonNode items = node.get("items");
                return items == null ? null : descend(items, segment);
            }
            if (node.isArray()) {
                node = node.get("items");
                continue;
            }
            // {"type":"array","items":...} and oneOf/anyOf wrappers
            JsonNode items = node.get("items");
            if (items != null && (node.get("type") == null || node.get("type").asText("").equals("array"))) {
                node = items;
                continue;
            }
            JsonNode anyOf = node.get("anyOf");
            if (anyOf == null) {
                anyOf = node.get("oneOf");
            }
            if (anyOf != null && anyOf.isArray()) {
                for (JsonNode branch : anyOf) {
                    JsonNode hit = descend(branch, segment);
                    if (hit != null) {
                        return hit;
                    }
                }
                return null;
            }
            return null;
        }
        return null;
    }

    /** {@code premium.breakdown[].amount} → ["premium", "breakdown", "amount"]. */
    private static List<String> segments(String fieldPath) {
        List<String> out = new ArrayList<>();
        for (String part : fieldPath.split("\\.")) {
            out.add(part.endsWith("[]") ? part.substring(0, part.length() - 2) : part);
        }
        return out;
    }

    /** One segment list is a prefix of the other ({@code []} markers equated). */
    private static boolean overlaps(String a, String b) {
        List<String> sa = segments(a);
        List<String> sb = segments(b);
        List<String> shorter = sa.size() <= sb.size() ? sa : sb;
        List<String> longer = shorter == sa ? sb : sa;
        for (int i = 0; i < shorter.size(); i++) {
            if (!shorter.get(i).equals(longer.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ accumulation

    /**
     * True when a delta finding of THIS hop already explains the drift: same method +
     * path shape, and either the same overlapping field, or an operation-wide removal
     * (field-less OPERATION/PATH/REQUEST_BODY finding at that location).
     */
    static boolean coveredByDelta(Accumulated acc, List<CompatibilityFinding> deltaFindings) {
        for (CompatibilityFinding delta : deltaFindings) {
            if (delta.method() == null || !delta.method().equals(acc.method)) {
                continue;
            }
            LocationKeys.Operation op = LocationKeys.operationOf(delta.location() == null
                    ? "" : delta.location());
            if (!op.shape().equals(acc.shape)) {
                continue;
            }
            if (delta.fieldPath() == null || delta.fieldPath().isBlank()) {
                return true; // operation-wide change (removal/shape/body) covers everything
            }
            if (kindMatchesScope(acc.kind, delta.scope()) && overlaps(delta.fieldPath(), acc.field)) {
                return true;
            }
        }
        return false;
    }

    /** reads-drift is explained by RESPONSE findings; sends-drift by REQUEST/PARAM ones. */
    private static boolean kindMatchesScope(String kind, String scope) {
        return switch (kind) {
            case "reads" -> scope != null && (scope.startsWith("RESPONSE")
                    || scope.equals("OPERATION"));
            case "sends" -> scope != null && (scope.startsWith("REQUEST") || scope.startsWith("PARAM")
                    || scope.equals("OPERATION"));
            default -> false;
        };
    }

    /** Groups identical drift across consumers into one finding with named consumers. */
    private static final class Accumulated {
        final String method;
        final String shape;
        final String aliasPath;
        final String kind;
        final String field;
        final List<String> consumers = new ArrayList<>();

        Accumulated(DeclaredOperation binding, String field, String kind, String aliasPath) {
            this.method = binding.method();
            this.shape = binding.pathShape();
            this.aliasPath = aliasPath;
            this.kind = kind;
            this.field = field;
        }

        String detail() {
            return switch (kind) {
                case "binding" -> "consumer declaration stale: the " + method + " " + aliasPath
                        + " operation it references does not exist in the incoming spec";
                case "reads" -> "consumer declaration drift: " + aliasPath + " no longer documents '"
                        + field + "' in any response (removed in this or an earlier published version)";
                default -> "consumer declaration drift: " + aliasPath + " no longer accepts '"
                        + field + "' (removed from the request contract in this or an earlier version)";
            };
        }

        String kind() {
            return kind;
        }
    }

    private static void accumulate(Map<String, Accumulated> drift, DeclaredOperation binding,
                                   String field, String kind, List<String> consumer) {
        String key = binding.method() + " " + binding.pathShape() + "|" + kind + "|" + (field == null ? "" : field);
        Accumulated acc = drift.computeIfAbsent(key,
                k -> new Accumulated(binding, field, kind, binding.rawPath()));
        if (!acc.consumers.contains(consumer.get(0))) {
            acc.consumers.add(consumer.get(0));
        }
    }

    // ------------------------------------------------------------------ doc helpers

    private static String opKey(String method, String shape) {
        return method.toUpperCase(Locale.ROOT) + " " + shape;
    }

    /** All operations of a spec with their effective-schema scope keys and raw-path alias. */
    private static Map<String, OperationScopes> operationsOf(SpecDocument doc) {
        Map<String, OperationScopes> out = new TreeMap<>();
        Map<String, String> effective = doc.getEffectiveSchemas();
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            LocationKeys.Operation op = LocationKeys.operationOf(entry.getKey());
            String key = opKey(op.verb(), op.shape());
            OperationScopes scopes = out.get(key);
            if (scopes == null) {
                String alias = doc.getPathAliases().getOrDefault(op.shape(), op.shape());
                scopes = new OperationScopes(op.verb(), op.shape(), alias, new ArrayList<>());
                out.put(key, scopes);
            }
            scopes.scopeKeys().add(LocationKeys.scopeOf(entry.getKey()));
        }
        return out;
    }

    private static JsonNode parseSchema(String json) {
        if (json == null || json.isBlank() || json.equals("null")) {
            return null;
        }
        try {
            return JsonMapperHolder.MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class JsonMapperHolder {
        private static final tools.jackson.databind.json.JsonMapper MAPPER =
                tools.jackson.databind.json.JsonMapper.builder().build();
    }

    private static List<String> sorted(List<String> paths) {
        return paths.stream().sorted().toList();
    }
}
