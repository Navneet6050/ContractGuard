package com.contractguard.compatibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compatibility engine: diffs two EFFECTIVE wire-contract maps produced by the specs
 * module (key = "VERB /pathShape :: scope", value = canonical expanded schema JSON).
 * It deliberately knows nothing about YAML, $refs, consumers or versions — the specs
 * module has already resolved and canonicalized everything.
 *
 * Semantics follow the guarantee-asymmetry principle (PLAN.md §6):
 * - REQUEST side: the space of accepted requests may only grow. Narrowing
 *   (required added, property removed, enum/type/constraint tightened) is BREAKING.
 * - RESPONSE side: the guaranteed response space may only grow in promise.
 *   Weakened guarantees (required removed, property removed, enum/type widened)
 *   are BREAKING; strengthened guarantees are INFO.
 * - Cosmetic reorganization never reaches this engine: equal effective maps
 *   (renamed components, inlined objects, renamed path parameters) produce ZERO findings.
 */
public final class CompatibilityEngine {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Constraint keys with directional tightness semantics (see {@link #tightness}). */
    private static final List<String> CONSTRAINT_KEYS = List.of(
            "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
            "minProperties", "maxProperties",
            "pattern", "multipleOf", "format", "exclusiveMinimum", "exclusiveMaximum", "uniqueItems");

    /** Sentinel for constraint changes whose direction cannot be determined. */
    private static final int UNKNOWN_DIRECTION = Integer.MIN_VALUE;

    /** +1 = new value is tighter, -1 = looser, 0 = no directional change, UNKNOWN = unordered. */
    private static int tightness(String key, JsonNode oldValue, JsonNode newValue) {
        boolean bothNumbers = oldValue.isNumber() && newValue.isNumber();
        return switch (key) {
            case "minimum", "minLength", "minItems", "minProperties" -> Double.compare(num(newValue), num(oldValue));
            case "maximum", "maxLength", "maxItems", "maxProperties" -> Double.compare(num(oldValue), num(newValue));
            // OpenAPI 3.1 writes exclusive bounds as numbers, 3.0 as booleans beside min/max
            case "exclusiveMinimum" -> bothNumbers
                    ? Double.compare(num(newValue), num(oldValue))
                    : booleanTightness(oldValue, newValue);
            case "exclusiveMaximum" -> bothNumbers
                    ? Double.compare(num(oldValue), num(newValue))
                    : booleanTightness(oldValue, newValue);
            // Regular languages are not ordered: a pattern change is directional only in
            // the trivial all-accepting case; otherwise the direction is unknowable.
            case "pattern" -> isAcceptAll(newValue) ? -1 : isAcceptAll(oldValue) ? 1 : UNKNOWN_DIRECTION;
            // For multiples, divisibility decides the direction (multiples of 4 are a
            // subset of multiples of 2); anything else is unordered.
            case "multipleOf" -> multipleOfTightness(oldValue, newValue);
            // Format vocabularies are unordered name sets.
            case "format" -> UNKNOWN_DIRECTION;
            case "uniqueItems" -> booleanTightness(oldValue, newValue);
            default -> 0;
        };
    }

    private static int booleanTightness(JsonNode oldValue, JsonNode newValue) {
        return newValue.booleanValue() && !oldValue.booleanValue() ? 1
                : oldValue.booleanValue() && !newValue.booleanValue() ? -1 : 0;
    }

    private static int multipleOfTightness(JsonNode oldValue, JsonNode newValue) {
        if (!(oldValue.isNumber() && newValue.isNumber())) {
            return UNKNOWN_DIRECTION;
        }
        try {
            java.math.BigDecimal oldM = oldValue.decimalValue();
            java.math.BigDecimal newM = newValue.decimalValue();
            if (oldM.signum() <= 0 || newM.signum() <= 0) {
                return UNKNOWN_DIRECTION;
            }
            boolean newDividesOld = oldM.remainder(newM).signum() == 0;
            boolean oldDividesNew = newM.remainder(oldM).signum() == 0;
            if (newDividesOld && oldDividesNew) {
                return 0; // same multiples (e.g. 4 vs 4.0): no directional change
            }
            if (newDividesOld) {
                return -1; // old multiples are a subset of new multiples: loosened
            }
            if (oldDividesNew) {
                return 1; // new multiples are a subset of old multiples: tightened
            }
            // neither divides the other (e.g. 3 -> 5): the accepted sets are unordered
            return UNKNOWN_DIRECTION;
        } catch (ArithmeticException e) {
            return UNKNOWN_DIRECTION;
        }
    }

    /** True for trivial all-accepting patterns (".*", optionally anchored). */
    private static boolean isAcceptAll(JsonNode pattern) {
        if (pattern == null || !pattern.isString()) {
            return false;
        }
        String value = pattern.stringValue();
        return value.matches("^\\^?\\.\\*\\$?$");
    }

    private static double num(JsonNode node) {
        return node.isNumber() ? node.numberValue().doubleValue() : 0d;
    }

    // ------------------------------------------------------------------ entry point

    public List<CompatibilityFinding> diff(Map<String, String> oldEffective,
                                           Map<String, String> newEffective) {
        return diff(oldEffective, newEffective, Map.of(), Map.of());
    }

    /**
     * @param oldPathAliases shape → raw path for the OLD spec (display only)
     * @param newPathAliases shape → raw path for the NEW spec (preferred for display)
     */
    public List<CompatibilityFinding> diff(Map<String, String> oldEffective,
                                           Map<String, String> newEffective,
                                           Map<String, String> oldPathAliases,
                                           Map<String, String> newPathAliases) {
        List<CompatibilityFinding> out = new ArrayList<>();
        Map<LocationKeys.Operation, Map<String, String>> oldOps = groupByOperation(oldEffective);
        Map<LocationKeys.Operation, Map<String, String>> newOps = groupByOperation(newEffective);

        TreeSet<LocationKeys.Operation> operations = new TreeSet<>();
        operations.addAll(oldOps.keySet());
        operations.addAll(newOps.keySet());

        for (LocationKeys.Operation op : operations) {
            Map<String, String> oldScopes = oldOps.get(op);
            Map<String, String> newScopes = newOps.get(op);
            String path = displayPath(op.shape(), oldPathAliases, newPathAliases);
            if (oldScopes == null) {
                out.add(new CompatibilityFinding("OPERATION.ADDED", Severity.INFO, "OPERATION",
                        op.verb(), path, op.verb() + " " + op.shape(), null,
                        "new operation", null, null));
                continue;
            }
            if (newScopes == null) {
                out.add(new CompatibilityFinding("OPERATION.REMOVED", Severity.BREAKING, "OPERATION",
                        op.verb(), path, op.verb() + " " + op.shape(), null,
                        "operation removed; dropped wire locations: "
                                + String.join(", ", new TreeMap<>(oldScopes).keySet()),
                        null, null));
                continue;
            }
            diffOperation(op, path, oldScopes, newScopes, out);
        }

        out.sort(Comparator
                .comparing(CompatibilityFinding::location, Comparator.nullsLast(String::compareTo))
                .thenComparing(CompatibilityFinding::fieldPath, Comparator.nullsLast(String::compareTo))
                .thenComparing(CompatibilityFinding::code));
        return List.copyOf(out);
    }

    private static String displayPath(String shape, Map<String, String> oldAliases,
                                      Map<String, String> newAliases) {
        String alias = newAliases.get(shape);
        if (alias == null) {
            alias = oldAliases.get(shape);
        }
        return alias != null ? alias : shape;
    }

    private static Map<LocationKeys.Operation, Map<String, String>> groupByOperation(
            Map<String, String> effective) {
        Map<LocationKeys.Operation, Map<String, String>> ops = new TreeMap<>();
        effective.forEach((key, value) ->
                ops.computeIfAbsent(LocationKeys.operationOf(key), op -> new TreeMap<>())
                        .put(key, value));
        return ops;
    }

    // ------------------------------------------------------------------ per operation

    private void diffOperation(LocationKeys.Operation op, String path,
                               Map<String, String> oldScopes, Map<String, String> newScopes,
                               List<CompatibilityFinding> out) {
        diffRequestBodies(op, path, oldScopes, newScopes, out);
        diffParameters(op, path, oldScopes, newScopes, out);
        diffResponses(op, path, oldScopes, newScopes, out);
    }

    private void diffRequestBodies(LocationKeys.Operation op, String path,
                                   Map<String, String> oldScopes, Map<String, String> newScopes,
                                   List<CompatibilityFinding> out) {
        Map<String, String> oldBodies = filterByPrefix(oldScopes, "requestBody");
        Map<String, String> newBodies = filterByPrefix(newScopes, "requestBody");
        Map<String, String> oldByMedia = new TreeMap<>();
        oldBodies.forEach((key, value) -> oldByMedia.put(LocationKeys.requestBodyMedia(LocationKeys.scopeOf(key)), value));
        Map<String, String> newByMedia = new TreeMap<>();
        newBodies.forEach((key, value) -> newByMedia.put(LocationKeys.requestBodyMedia(LocationKeys.scopeOf(key)), value));

        for (Map.Entry<String, String> media : oldByMedia.entrySet()) {
            if (!newByMedia.containsKey(media.getKey())) {
                out.add(bodyFinding(op, path, "REQUEST_BODY.REMOVED", Severity.BREAKING, media.getKey(),
                        "request body media type '" + media.getKey() + "' removed", media.getValue(), null));
            }
        }
        for (Map.Entry<String, String> media : newByMedia.entrySet()) {
            String oldJson = oldByMedia.get(media.getKey());
            if (oldJson == null) {
                out.add(bodyFinding(op, path, "REQUEST_BODY.ADDED", Severity.INFO, media.getKey(),
                        "request body media type '" + media.getKey() + "' added", null, media.getValue()));
            } else {
                diffSchema(op, path, bodyLocation(op, media.getKey()), SchemaSide.REQUEST,
                        oldJson, media.getValue(), "", out);
            }
        }
    }

    /**
     * Parameters are matched by (in, ordinal) — the specs module drops parameter NAMES
     * from the effective envelope precisely so that a parameter rename stays invisible.
     * This assumes stable parameter ordering within an operation (true for the kit and
     * for specifications that append rather than reorder parameters).
     */
    private void diffParameters(LocationKeys.Operation op, String path,
                                Map<String, String> oldScopes, Map<String, String> newScopes,
                                List<CompatibilityFinding> out) {
        Map<String, String> oldParams = filterByPrefix(oldScopes, "param:");
        Map<String, String> newParams = filterByPrefix(newScopes, "param:");

        for (Map.Entry<String, String> param : oldParams.entrySet()) {
            if (!newParams.containsKey(param.getKey())) {
                out.add(new CompatibilityFinding("PARAM.REMOVED", Severity.BREAKING, "PARAM",
                        op.verb(), path, param.getKey(), null,
                        "parameter removed", param.getValue(), null));
            }
        }
        for (Map.Entry<String, String> param : newParams.entrySet()) {
            String oldJson = oldParams.get(param.getKey());
            if (oldJson == null) {
                boolean required = envelopeRequired(param.getValue());
                out.add(new CompatibilityFinding(
                        required ? "PARAM.REQUIRED_ADDED" : "PARAM.ADDED_OPTIONAL",
                        required ? Severity.BREAKING : Severity.INFO, "PARAM",
                        op.verb(), path, param.getKey(), null,
                        required ? "new parameter that is required" : "new optional parameter",
                        null, param.getValue()));
            } else {
                diffParamEnvelope(op, path, param.getKey(), oldJson, param.getValue(), out);
            }
        }
    }

    /** Reads the "required" flag from a parameter envelope JSON produced by the specs module. */
    private static boolean envelopeRequired(String envelopeJson) {
        JsonNode envelope = parseJson(envelopeJson);
        return boolValue(envelope, "required");
    }

    private void diffParamEnvelope(LocationKeys.Operation op, String path, String locationKey,
                                   String oldJson, String newJson, List<CompatibilityFinding> out) {
        JsonNode oldEnvelope = parseJson(oldJson);
        JsonNode newEnvelope = parseJson(newJson);
        boolean oldRequired = boolValue(oldEnvelope, "required");
        boolean newRequired = boolValue(newEnvelope, "required");
        if (newRequired && !oldRequired) {
            out.add(new CompatibilityFinding("PARAM.REQUIRED_ADDED", Severity.BREAKING, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter became required", oldJson, newJson));
        } else if (oldRequired && !newRequired) {
            out.add(new CompatibilityFinding("PARAM.REQUIRED_REMOVED", Severity.INFO, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter is no longer required", oldJson, newJson));
        }
        JsonNode oldSchema = oldEnvelope.get("schema");
        JsonNode newSchema = newEnvelope.get("schema");
        if (isNullish(oldSchema) && !isNullish(newSchema)) {
            // request side: a documented schema narrows what may be sent (asymmetry)
            out.add(new CompatibilityFinding("REQ.SCHEMA_ADDED", Severity.BREAKING, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter schema added", null, JsonWriter.write(newSchema)));
        } else if (!isNullish(oldSchema) && isNullish(newSchema)) {
            out.add(new CompatibilityFinding("REQ.SCHEMA_REMOVED", Severity.INFO, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter schema removed", JsonWriter.write(oldSchema), null));
        } else if (!isNullish(oldSchema)) {
            diffSchemaNode(op, path, locationKey, SchemaSide.REQUEST, oldSchema, newSchema, "", out);
        }
        diffParamSerialization(op, path, locationKey, oldEnvelope, newEnvelope,
                isNullish(oldSchema) ? null : oldSchema,
                isNullish(newSchema) ? null : newSchema, out);
    }

    /**
     * Serialization attributes (style / explode / allowReserved) define how the
     * parameter value appears on the wire; the specs module records their deviations
     * from the OpenAPI defaults under the envelope's {@code "x-serialization"}.
     *
     * <ul>
     *   <li>A style change always alters the wire format (prefixes and delimiters),
     *       so it is BREAKING: clients built against the old spec send the old
     *       format to a server implementing the new one.</li>
     *   <li>An explode change alters the wire format only for multi-valued
     *       (array/object) parameters; for single-valued parameters it is visible
     *       but wire-neutral (INFO, not guessed to be breaking).</li>
     *   <li>{@code allowReserved} (query-only) gates whether reserved characters may
     *       travel unencoded: disabling it can reject what existing clients send
     *       (BREAKING); enabling it only grants permission (INFO).</li>
     * </ul>
     *
     * <p>explode is only compared when both sides have the same style: when the style
     * differs the style finding already covers the wire change (explode defaults are
     * style-dependent, so a style flip would otherwise report a spurious second
     * finding).
     */
    private void diffParamSerialization(LocationKeys.Operation op, String path, String locationKey,
                                        JsonNode oldEnvelope, JsonNode newEnvelope,
                                        JsonNode oldSchema, JsonNode newSchema,
                                        List<CompatibilityFinding> out) {
        String in = paramIn(locationKey);
        if (in.isEmpty()) {
            return;
        }
        boolean query = "query".equals(in);
        String defaultStyle = query || "cookie".equals(in) ? "form" : "simple";
        JsonNode oldSer = oldEnvelope.get("x-serialization");
        JsonNode newSer = newEnvelope.get("x-serialization");
        String oldStyle = serializationString(oldSer, "style", defaultStyle);
        String newStyle = serializationString(newSer, "style", defaultStyle);
        if (!oldStyle.equals(newStyle)) {
            out.add(new CompatibilityFinding("PARAM.SERIALIZATION_CHANGED", Severity.BREAKING,
                    "PARAM", op.verb(), path, locationKey, null,
                    "serialization style changed from '" + oldStyle + "' to '" + newStyle
                            + "' (the wire format of this parameter changes)",
                    JsonWriter.write(oldSer), JsonWriter.write(newSer)));
        } else {
            boolean oldExplode = serializationBool(oldSer, "explode", "form".equals(oldStyle));
            boolean newExplode = serializationBool(newSer, "explode", "form".equals(newStyle));
            if (oldExplode != newExplode) {
                boolean multiValued = multiValuedSchema(oldSchema) || multiValuedSchema(newSchema);
                out.add(new CompatibilityFinding("PARAM.SERIALIZATION_CHANGED",
                        multiValued ? Severity.BREAKING : Severity.INFO, "PARAM",
                        op.verb(), path, locationKey, null,
                        "explode changed from " + oldExplode + " to " + newExplode
                                + (multiValued
                                        ? " (the wire format changes for array/object values)"
                                        : " (single-valued parameter: no wire format change)"),
                        JsonWriter.write(oldSer), JsonWriter.write(newSer)));
            }
        }
        if (query) {
            boolean oldAllow = serializationBool(oldSer, "allowReserved", false);
            boolean newAllow = serializationBool(newSer, "allowReserved", false);
            if (oldAllow && !newAllow) {
                out.add(new CompatibilityFinding("PARAM.SERIALIZATION_CHANGED", Severity.BREAKING,
                        "PARAM", op.verb(), path, locationKey, null,
                        "allowReserved disabled: unencoded reserved characters are no longer allowed",
                        JsonWriter.write(oldSer), JsonWriter.write(newSer)));
            } else if (!oldAllow && newAllow) {
                out.add(new CompatibilityFinding("PARAM.SERIALIZATION_CHANGED", Severity.INFO,
                        "PARAM", op.verb(), path, locationKey, null,
                        "allowReserved enabled: reserved characters may now be sent unencoded",
                        JsonWriter.write(oldSer), JsonWriter.write(newSer)));
            }
        }
    }

    /** "POST /a/{0} :: param:path:1" → "path" ("" when the scope is not a parameter). */
    private static String paramIn(String locationKey) {
        String scope = LocationKeys.scopeOf(locationKey);
        if (!scope.startsWith("param:")) {
            return "";
        }
        String rest = scope.substring("param:".length());
        int ordinal = rest.lastIndexOf(':');
        return ordinal < 0 ? rest : rest.substring(0, ordinal);
    }

    private static String serializationString(JsonNode serialization, String field, String fallback) {
        JsonNode value = serialization == null ? null : serialization.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue()
                : fallback;
    }

    private static boolean serializationBool(JsonNode serialization, String field, boolean fallback) {
        JsonNode value = serialization == null ? null : serialization.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : fallback;
    }

    /** True when the schema can carry more than one value (array or object). */
    private static boolean multiValuedSchema(JsonNode schema) {
        if (isNullish(schema) || !schema.isObject()) {
            return false;
        }
        Set<String> types = typeSet(schema);
        return types.contains("array") || types.contains("object");
    }

    private void diffResponses(LocationKeys.Operation op, String path,
                               Map<String, String> oldScopes, Map<String, String> newScopes,
                               List<CompatibilityFinding> out) {
        Map<String, Map<String, String>> oldByStatus = groupResponses(oldScopes);
        Map<String, Map<String, String>> newByStatus = groupResponses(newScopes);

        TreeSet<String> statuses = new TreeSet<>();
        statuses.addAll(oldByStatus.keySet());
        statuses.addAll(newByStatus.keySet());

        for (String status : statuses) {
            Map<String, String> oldMedia = oldByStatus.get(status);
            Map<String, String> newMedia = newByStatus.get(status);
            if (oldMedia == null) {
                out.add(responseFinding(op, path, "RESP.RESPONSE_ADDED", Severity.INFO, status, null,
                        "new documented response '" + status + "'", null, firstValue(newMedia)));
                continue;
            }
            if (newMedia == null) {
                out.add(responseFinding(op, path, "RESP.RESPONSE_REMOVED", Severity.BREAKING, status, null,
                        "documented response '" + status + "' removed", firstValue(oldMedia), null));
                continue;
            }
            boolean oldSchemaless = oldMedia.size() == 1 && oldMedia.containsKey("");
            boolean newSchemaless = newMedia.size() == 1 && newMedia.containsKey("");
            if (oldSchemaless && !newSchemaless) {
                out.add(responseFinding(op, path, "RESP.RESPONSE_SCHEMA_ADDED", Severity.INFO, status, null,
                        "response '" + status + "' now documents a body", null,
                        firstValue(newMedia)));
                continue;
            }
            if (newSchemaless && !oldSchemaless) {
                out.add(responseFinding(op, path, "RESP.MEDIA_TYPE_REMOVED", Severity.BREAKING, status, null,
                        "response '" + status + "' no longer documents a body", firstValue(oldMedia), null));
                continue;
            }
            for (Map.Entry<String, String> media : oldMedia.entrySet()) {
                if (media.getKey().isEmpty()) {
                    continue;
                }
                if (!newMedia.containsKey(media.getKey())) {
                    out.add(responseFinding(op, path, "RESP.MEDIA_TYPE_REMOVED", Severity.BREAKING, status,
                            media.getKey(),
                            "response media type '" + media.getKey() + "' removed", media.getValue(), null));
                }
            }
            for (Map.Entry<String, String> media : newMedia.entrySet()) {
                if (media.getKey().isEmpty()) {
                    continue;
                }
                String oldJson = oldMedia.get(media.getKey());
                if (oldJson == null) {
                    out.add(responseFinding(op, path, "RESP.MEDIA_TYPE_ADDED", Severity.INFO, status,
                            media.getKey(), "response media type '" + media.getKey() + "' added",
                            null, media.getValue()));
                } else {
                    diffSchema(op, path, responseLocation(op, status, media.getKey()),
                            SchemaSide.RESPONSE, oldJson, media.getValue(), "", out);
                }
            }
        }
    }

    private static Map<String, Map<String, String>> groupResponses(Map<String, String> scopes) {
        Map<String, Map<String, String>> byStatus = new TreeMap<>();
        scopes.forEach((key, value) -> {
            String scope = LocationKeys.scopeOf(key);
            if (!LocationKeys.isResponse(scope)) {
                return;
            }
            String status = LocationKeys.responseStatus(scope);
            String media = LocationKeys.responseMedia(scope);
            byStatus.computeIfAbsent(status, s -> new TreeMap<>()).put(media, value);
        });
        return byStatus;
    }

    private static String firstValue(Map<String, String> mediaMap) {
        return mediaMap == null || mediaMap.isEmpty() ? null : mediaMap.values().iterator().next();
    }

    private static Map<String, String> filterByPrefix(Map<String, String> scopes, String prefix) {
        Map<String, String> out = new TreeMap<>();
        scopes.forEach((key, value) -> {
            String scope = LocationKeys.scopeOf(key);
            if (scope.startsWith(prefix)) {
                out.put(key, value);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ schema level

    private void diffSchema(LocationKeys.Operation op, String path, String locationKey,
                            SchemaSide side, String oldJson, String newJson, String fieldPath,
                            List<CompatibilityFinding> out) {
        JsonNode oldNode = parseJson(oldJson);
        JsonNode newNode = parseJson(newJson);
        boolean oldNull = isNullish(oldNode);
        boolean newNull = isNullish(newNode);
        if (oldNull && newNull) {
            return;
        }
        if (oldNull) {
            // Request side: documenting a schema where none existed NARROWS the
            // documented acceptance space (mirrors the type-narrowing rule, which
            // treats "no type" as the widest set). Response side: a newly documented
            // promise is additive, so it stays informational.
            out.add(new CompatibilityFinding(side.prefix() + ".SCHEMA_ADDED",
                    side == SchemaSide.REQUEST ? Severity.BREAKING : Severity.INFO,
                    side.name(), op.verb(), path, locationKey, fieldPath,
                    "schema added where none was documented", null, JsonWriter.write(newNode)));
            return;
        }
        if (newNull) {
            out.add(new CompatibilityFinding(side.prefix() + ".SCHEMA_REMOVED",
                    side == SchemaSide.REQUEST ? Severity.INFO : Severity.BREAKING,
                    side.name(), op.verb(), path, locationKey, fieldPath,
                    "documented schema removed", JsonWriter.write(oldNode), null));
            return;
        }
        diffSchemaNode(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
    }

    private void diffSchemaNode(LocationKeys.Operation op, String path, String locationKey,
                                SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                String fieldPath, List<CompatibilityFinding> out) {
        TypeRelation typeRelation = compareType(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        if (typeRelation == TypeRelation.DISJOINT) {
            return; // types diverged entirely; deeper structural comparison would be noise
        }
        compareEnum(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareNullable(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareConstraints(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareDefault(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareProperties(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareItems(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareAdditionalProperties(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
        compareCompositions(op, path, locationKey, side, oldNode, newNode, fieldPath, out);
    }

    private enum TypeRelation { EQUAL, NARROWED, WIDENED, DISJOINT }

    private TypeRelation compareType(LocationKeys.Operation op, String path, String locationKey,
                                     SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                     String fieldPath, List<CompatibilityFinding> out) {
        Set<String> oldTypes = typeSet(oldNode);
        Set<String> newTypes = typeSet(newNode);
        if (oldTypes.equals(newTypes)) {
            return TypeRelation.EQUAL;
        }
        TypeRelation relation;
        if (oldTypes.isEmpty() || newTypes.isEmpty()) {
            relation = oldTypes.isEmpty() ? TypeRelation.NARROWED : TypeRelation.WIDENED;
        } else if (oldTypes.containsAll(newTypes)) {
            relation = TypeRelation.NARROWED;
        } else if (newTypes.containsAll(oldTypes)) {
            relation = TypeRelation.WIDENED;
        } else {
            relation = TypeRelation.DISJOINT;
        }
        switch (relation) {
            case NARROWED -> emit(op, path, locationKey, side, fieldPath,
                    "REQ.TYPE_NARROWED", Severity.BREAKING, "RESP.TYPE_NARROWED", Severity.INFO,
                    "accepted type(s) narrowed from " + oldTypes + " to " + newTypes,
                    JsonWriter.write(oldNode.get("type")), JsonWriter.write(newNode.get("type")), out);
            case WIDENED -> emit(op, path, locationKey, side, fieldPath,
                    "REQ.TYPE_WIDENED", Severity.INFO, "RESP.TYPE_WIDENED", Severity.BREAKING,
                    "accepted type(s) widened from " + oldTypes + " to " + newTypes,
                    JsonWriter.write(oldNode.get("type")), JsonWriter.write(newNode.get("type")), out);
            case DISJOINT -> emit(op, path, locationKey, side, fieldPath,
                    "REQ.TYPE_NARROWED", Severity.BREAKING, "RESP.TYPE_CHANGED", Severity.BREAKING,
                    "type changed from " + oldTypes + " to " + newTypes,
                    JsonWriter.write(oldNode.get("type")), JsonWriter.write(newNode.get("type")), out);
            default -> { /* EQUAL: nothing */ }
        }
        return relation;
    }

    private void compareEnum(LocationKeys.Operation op, String path, String locationKey,
                             SchemaSide side, JsonNode oldNode, JsonNode newNode,
                             String fieldPath, List<CompatibilityFinding> out) {
        Set<String> oldValues = enumSet(oldNode);
        Set<String> newValues = enumSet(newNode);
        if (oldValues.equals(newValues)) {
            return;
        }
        Set<String> removed = difference(oldValues, newValues);
        Set<String> added = difference(newValues, oldValues);
        if (!removed.isEmpty()) {
            emit(op, path, locationKey, side, fieldPath,
                    "REQ.ENUM_NARROWED", Severity.BREAKING, "RESP.ENUM_NARROWED", Severity.INFO,
                    "enum value(s) removed: " + removed,
                    JsonWriter.write(oldNode.get("enum")), JsonWriter.write(newNode.get("enum")), out);
        }
        if (!added.isEmpty()) {
            emit(op, path, locationKey, side, fieldPath,
                    "REQ.ENUM_WIDENED", Severity.INFO, "RESP.ENUM_WIDENED", Severity.BREAKING,
                    "enum value(s) added: " + added,
                    JsonWriter.write(oldNode.get("enum")), JsonWriter.write(newNode.get("enum")), out);
        }
    }

    private void compareNullable(LocationKeys.Operation op, String path, String locationKey,
                                 SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                 String fieldPath, List<CompatibilityFinding> out) {
        boolean oldNullable = boolValue(oldNode, "nullable");
        boolean newNullable = boolValue(newNode, "nullable");
        if (oldNullable == newNullable) {
            return;
        }
        if (newNullable) {
            emit(op, path, locationKey, side, fieldPath,
                    "REQ.TYPE_WIDENED", Severity.INFO, "RESP.NULLABLE_ADDED", Severity.BREAKING,
                    "null values are now accepted",
                    JsonWriter.write(oldNode.get("nullable")), JsonWriter.write(newNode.get("nullable")), out);
        } else {
            emit(op, path, locationKey, side, fieldPath,
                    "REQ.CONSTRAINT_TIGHTENED", Severity.BREAKING, "RESP.CONSTRAINT_TIGHTENED", Severity.INFO,
                    "null values are no longer accepted",
                    JsonWriter.write(oldNode.get("nullable")), JsonWriter.write(newNode.get("nullable")), out);
        }
    }

    private void compareConstraints(LocationKeys.Operation op, String path, String locationKey,
                                    SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                    String fieldPath, List<CompatibilityFinding> out) {
        for (String key : CONSTRAINT_KEYS) {
            JsonNode oldValue = oldNode.get(key);
            JsonNode newValue = newNode.get(key);
            boolean oldPresent = oldValue != null && !isNullish(oldValue);
            boolean newPresent = newValue != null && !isNullish(newValue);
            if (!oldPresent && !newPresent) {
                continue;
            }
            if (oldPresent && newPresent && oldValue.equals(newValue)) {
                continue;
            }
            int direction;
            String verb;
            if (oldPresent && newPresent) {
                direction = tightness(key, oldValue, newValue);
                verb = "changed";
            } else if (newPresent) {
                // adding an all-accepting pattern constrains nothing
                direction = "pattern".equals(key) && isAcceptAll(newValue) ? 0 : 1;
                verb = "added";
            } else {
                // removing an all-accepting pattern releases nothing
                direction = "pattern".equals(key) && isAcceptAll(oldValue) ? 0 : -1;
                verb = "removed";
            }
            if (direction == 0) {
                continue;
            }
            String detail = "'" + key + "' " + verb
                    + (newPresent ? ": " + JsonWriter.write(newValue) : "")
                    + (oldPresent && newPresent ? " (was " + JsonWriter.write(oldValue) : "");
            if (oldPresent && newPresent) {
                detail += ")";
            }
            if (direction == UNKNOWN_DIRECTION) {
                // The values differ but neither is a subset of the other — flag the
                // change explicitly instead of guessing a direction (which used to
                // mislabel loosened patterns as tightenings).
                out.add(new CompatibilityFinding(
                        side == SchemaSide.REQUEST ? "REQ.CONSTRAINT_CHANGED" : "RESP.CONSTRAINT_CHANGED",
                        Severity.INFO, side.name(), op.verb(), path, locationKey, fieldPath,
                        detail + " (direction cannot be determined)",
                        JsonWriter.write(oldValue), JsonWriter.write(newValue)));
                continue;
            }
            emitConstraintDirection(op, path, locationKey, side, fieldPath,
                    direction > 0, detail, JsonWriter.write(oldValue), JsonWriter.write(newValue), out);
        }
    }

    /**
     * Directional constraint finding: tightening narrows the request side (BREAKING)
     * or strengthens the response promise (INFO); loosening is the mirror image.
     */
    private void emitConstraintDirection(LocationKeys.Operation op, String path, String locationKey,
                                         SchemaSide side, String fieldPath, boolean tightened,
                                         String detail, String oldJson, String newJson,
                                         List<CompatibilityFinding> out) {
        boolean request = side == SchemaSide.REQUEST;
        out.add(new CompatibilityFinding(
                request ? (tightened ? "REQ.CONSTRAINT_TIGHTENED" : "REQ.CONSTRAINT_LOOSENED")
                        : (tightened ? "RESP.CONSTRAINT_TIGHTENED" : "RESP.CONSTRAINT_LOOSENED"),
                tightened == request ? Severity.BREAKING : Severity.INFO,
                side.name(), op.verb(), path, locationKey, fieldPath, detail, oldJson, newJson));
    }

    private void compareDefault(LocationKeys.Operation op, String path, String locationKey,
                                SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                String fieldPath, List<CompatibilityFinding> out) {
        if (side != SchemaSide.REQUEST) {
            return;
        }
        JsonNode oldDefault = oldNode.get("default");
        JsonNode newDefault = newNode.get("default");
        if (oldDefault == null || newDefault == null || oldDefault.equals(newDefault)) {
            return;
        }
        out.add(new CompatibilityFinding("REQ.DEFAULT_CHANGED", Severity.INFO, side.name(),
                op.verb(), path, locationKey, fieldPath,
                "default value changed",
                JsonWriter.write(oldDefault), JsonWriter.write(newDefault)));
    }

    /**
     * Property sets with rename pairing: a removed property and an added property whose
     * canonical schemas are deep-equal are reported once as PROPERTY_RENAMED (breaking)
     * instead of REMOVED+ADDED. Unmatched removals/ additions fall back to their own codes.
     */
    private void compareProperties(LocationKeys.Operation op, String path, String locationKey,
                                   SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                   String fieldPath, List<CompatibilityFinding> out) {
        JsonNode oldProps = oldNode.get("properties");
        JsonNode newProps = newNode.get("properties");
        Map<String, JsonNode> oldMap = propertyMap(oldProps);
        Map<String, JsonNode> newMap = propertyMap(newProps);

        List<String> removed = new ArrayList<>(difference(oldMap.keySet(), newMap.keySet()));
        List<String> added = new ArrayList<>(difference(newMap.keySet(), oldMap.keySet()));

        Map<String, String> renames = new LinkedHashMap<>();
        for (String oldName : removed) {
            String oldCanonical = JsonWriter.write(oldMap.get(oldName));
            for (String newName : added) {
                if (JsonWriter.write(newMap.get(newName)).equals(oldCanonical)) {
                    renames.put(oldName, newName);
                    break;
                }
            }
        }
        renames.keySet().forEach(removed::remove);
        renames.values().forEach(added::remove);

        for (Map.Entry<String, String> rename : renames.entrySet()) {
            if (!applicableOnSide(side, oldMap.get(rename.getKey()), newMap.get(rename.getValue()))) {
                continue; // the flagged side never carries this property on the wire
            }
            emit(op, path, locationKey, side, childPath(fieldPath, rename.getKey()),
                    "REQ.PROPERTY_RENAMED", Severity.BREAKING, "RESP.PROPERTY_RENAMED", Severity.BREAKING,
                    "property '" + rename.getKey() + "' renamed to '" + rename.getValue()
                            + "' (same shape)",
                    JsonWriter.write(oldMap.get(rename.getKey())),
                    JsonWriter.write(newMap.get(rename.getValue())), out);
        }
        for (String name : removed) {
            if (!applicableOnSide(side, oldMap.get(name), null)) {
                continue;
            }
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.PROPERTY_REMOVED", Severity.BREAKING, "RESP.PROPERTY_REMOVED", Severity.BREAKING,
                    "property '" + name + "' removed",
                    JsonWriter.write(oldMap.get(name)), null, out);
        }
        for (String name : added) {
            if (!applicableOnSide(side, null, newMap.get(name))) {
                continue;
            }
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.PROPERTY_ADDED", Severity.INFO, "RESP.PROPERTY_ADDED", Severity.INFO,
                    "property '" + name + "' added",
                    null, JsonWriter.write(newMap.get(name)), out);
        }

        // recurse into common properties
        for (String name : oldMap.keySet()) {
            JsonNode newChild = newMap.get(name);
            if (newChild != null && applicableOnSide(side, oldMap.get(name), newChild)) {
                diffSchemaNode(op, path, locationKey, side, oldMap.get(name), newChild,
                        childPath(fieldPath, name), out);
            }
        }

        compareRequired(op, path, locationKey, side, oldNode, newNode, oldMap, newMap, fieldPath, renames, out);
    }

    /**
     * OAS readOnly/writeOnly: a readOnly property is server-managed — it never
     * appears in accepted requests; a writeOnly property never appears in responses.
     * A side only hears about the property if it WAS or IS applicable to it (so the
     * flag itself appearing or disappearing stays visible as the property-level
     * change it is).
     */
    private static boolean applicableOnSide(SchemaSide side, JsonNode oldSchema, JsonNode newSchema) {
        String flag = side == SchemaSide.REQUEST ? "readOnly" : "writeOnly";
        boolean oldApplicable = oldSchema != null && !boolValue(oldSchema, flag);
        boolean newApplicable = newSchema != null && !boolValue(newSchema, flag);
        return oldApplicable || newApplicable;
    }

    private void compareRequired(LocationKeys.Operation op, String path, String locationKey,
                                 SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                 Map<String, JsonNode> oldProps, Map<String, JsonNode> newProps,
                                 String fieldPath, Map<String, String> renames,
                                 List<CompatibilityFinding> out) {
        Set<String> rawOldRequired = requiredSet(oldNode);
        Set<String> newRequired = requiredSet(newNode);
        // Renamed properties are re-keyed in the OLD required list, so renaming a
        // required field stays a single PROPERTY_RENAMED finding instead of also
        // masquerading as REQUIRED_REMOVED + REQUIRED_ADDED around it.
        Set<String> oldRequired = new TreeSet<>();
        for (String name : rawOldRequired) {
            oldRequired.add(renames.getOrDefault(name, name));
        }
        if (oldRequired.equals(newRequired)) {
            return;
        }
        for (String name : difference(newRequired, oldRequired)) {
            // a required readOnly property is never actually demanded of senders
            JsonNode newProp = newProps.get(name);
            if (side == SchemaSide.REQUEST && newProp != null && boolValue(newProp, "readOnly")) {
                continue;
            }
            JsonNode oldProp = oldProps.get(name);
            if (side == SchemaSide.RESPONSE && oldProp != null && boolValue(oldProp, "writeOnly")) {
                continue;
            }
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.REQUIRED_ADDED", Severity.BREAKING, "RESP.REQUIRED_ADDED", Severity.INFO,
                    "'" + name + "' added to required list"
                            + (side == SchemaSide.REQUEST ? "" : " (guarantee strengthened)"),
                    null, null, out);
        }
        for (String name : difference(oldRequired, newRequired)) {
            JsonNode oldProp = oldProps.get(name);
            if (side == SchemaSide.REQUEST && oldProp != null && boolValue(oldProp, "readOnly")) {
                continue;
            }
            JsonNode newProp = newProps.get(name);
            if (side == SchemaSide.RESPONSE && newProp != null && boolValue(newProp, "writeOnly")) {
                continue;
            }
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.REQUIRED_REMOVED", Severity.INFO, "RESP.REQUIRED_REMOVED", Severity.BREAKING,
                    "'" + name + "' removed from required list"
                            + (side == SchemaSide.RESPONSE ? " (no longer guaranteed)" : ""),
                    null, null, out);
        }
    }

    private void compareItems(LocationKeys.Operation op, String path, String locationKey,
                              SchemaSide side, JsonNode oldNode, JsonNode newNode,
                              String fieldPath, List<CompatibilityFinding> out) {
        JsonNode oldItems = oldNode.get("items");
        JsonNode newItems = newNode.get("items");
        boolean oldPresent = oldItems != null && !isNullish(oldItems);
        boolean newPresent = newItems != null && !isNullish(newItems);
        if (oldPresent && newPresent) {
            if (oldItems.isObject() && newItems.isObject()) {
                diffSchemaNode(op, path, locationKey, side, oldItems, newItems,
                        fieldPath + "[]", out);
            }
            return;
        }
        if (!oldPresent && !newPresent) {
            return;
        }
        // An element contract appearing or disappearing is a directional change, not a
        // no-op: on the request side it narrows/widens what arrays may be sent, on the
        // response side it strengthens/weakens the element guarantee.
        if (newPresent) {
            emitConstraintDirection(op, path, locationKey, side, fieldPath, true,
                    "array element contract added: " + JsonWriter.write(newItems),
                    null, JsonWriter.write(newItems), out);
        } else {
            emitConstraintDirection(op, path, locationKey, side, fieldPath, false,
                    "array element contract removed",
                    JsonWriter.write(oldItems), null, out);
        }
    }

    /**
     * additionalProperties: false forbids extra keys, a schema object constrains them,
     * true (or absence) allows anything. Transitions along that ladder are directional
     * (tightening/loosening); two object forms are diffed recursively like schemas.
     */
    private void compareAdditionalProperties(LocationKeys.Operation op, String path,
                                             String locationKey, SchemaSide side,
                                             JsonNode oldNode, JsonNode newNode,
                                             String fieldPath, List<CompatibilityFinding> out) {
        JsonNode oldValue = oldNode.get("additionalProperties");
        JsonNode newValue = newNode.get("additionalProperties");
        boolean oldPresent = oldValue != null && !isNullish(oldValue);
        boolean newPresent = newValue != null && !isNullish(newValue);
        if (oldPresent && newPresent && oldValue.isObject() && newValue.isObject()) {
            if (!oldValue.equals(newValue)) {
                diffSchemaNode(op, path, locationKey, side, oldValue, newValue,
                        fieldPath + "[*]", out);
            }
            return;
        }
        if (oldPresent && newPresent && oldValue.equals(newValue)) {
            return;
        }
        if (!oldPresent && !newPresent) {
            return;
        }
        int oldRank = additionalRank(oldValue, oldPresent);
        int newRank = additionalRank(newValue, newPresent);
        if (oldRank == newRank) {
            return; // e.g. absent ↔ true: both mean "any extra properties allowed"
        }
        boolean tightened = newRank < oldRank;
        String detail = "'additionalProperties' "
                + (tightened ? "tightened: extra properties are now constrained"
                             : "loosened: extra properties are no longer constrained")
                + (newPresent ? ": " + JsonWriter.write(newValue) : " (constraint removed)");
        emitConstraintDirection(op, path, locationKey, side, fieldPath, tightened, detail,
                JsonWriter.write(oldPresent ? oldValue : null),
                JsonWriter.write(newPresent ? newValue : null), out);
    }

    /** 0 = false (forbids extra keys), 1 = schema object, 2 = true or absent (unconstrained). */
    private static int additionalRank(JsonNode node, boolean present) {
        if (!present) {
            return 2;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 2 : 0;
        }
        return 1;
    }

    /**
     * Composition comparison at BRANCH granularity: branches that are deep-equal cancel
     * out (reordered anyOf/oneOf branches are cosmetic, not changes), the leftovers are
     * paired positionally when the list sizes match, and otherwise reported as branch
     * additions/removals. Branch removal narrows the documented space (request:
     * BREAKING, response: tighter promise = INFO); branch addition widens it (request:
     * INFO, response: widened promise = BREAKING). oneOf exclusivity is not analyzed —
     * its acceptance direction matches anyOf's, which is what the gate needs.
     */
    /**
     * Compares composition lists branch-wise. anyOf and oneOf are treated the same
     * (the visible set of alternatives is what changes): deep-equal branches cancel
     * regardless of position (reordering is cosmetic), same-size leftovers are paired
     * positionally for a recursive diff, and size changes emit
     * COMPOSITION_BRANCH_ADDED/REMOVED with the usual request/response asymmetry.
     *
     * <p>DOCUMENTED LIMITATION — oneOf exclusivity is NOT interpreted: the engine
     * never validates the "exactly one branch must match" constraint, so it cannot
     * detect (a) two branches being made mutually exclusive or (b) a removal that
     * widens the accepted set when branches overlap. Overlapping-branch exclusivity
     * is not decidable from the branch schemas alone without a solver, so the change
     * is reported with the conservative alternative-set semantics above rather than
     * guessed.
     */
    private void compareCompositions(LocationKeys.Operation op, String path, String locationKey,
                                     SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                     String fieldPath, List<CompatibilityFinding> out) {
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode oldList = oldNode.get(key);
            JsonNode newList = newNode.get(key);
            if (oldList == null && newList == null) {
                continue;
            }
            if (!isArray(oldList) || !isArray(newList)) {
                if (oldList != null && newList != null) {
                    // one side malformed (non-array): only an honest structural marker
                    out.add(new CompatibilityFinding(side.prefix() + ".COMPOSITION_CHANGED",
                            Severity.INFO, side.name(), op.verb(), path, locationKey, fieldPath,
                            "'" + key + "' composition list changed", null, null));
                }
                continue;
            }
            List<JsonNode> oldBranches = toArray(oldList);
            List<JsonNode> newBranches = toArray(newList);

            // cancel deep-equal branches (handles reordering and moved definitions)
            Map<String, List<Integer>> newRemaining = new LinkedHashMap<>();
            for (int j = 0; j < newBranches.size(); j++) {
                newRemaining.computeIfAbsent(JsonWriter.write(newBranches.get(j)), k -> new ArrayList<>())
                        .add(j);
            }
            List<Integer> oldLeftover = new ArrayList<>();
            for (int i = 0; i < oldBranches.size(); i++) {
                List<Integer> candidates = newRemaining.get(JsonWriter.write(oldBranches.get(i)));
                if (candidates != null && !candidates.isEmpty()) {
                    candidates.remove(candidates.size() - 1);
                    if (candidates.isEmpty()) {
                        newRemaining.remove(JsonWriter.write(oldBranches.get(i)));
                    }
                } else {
                    oldLeftover.add(i);
                }
            }
            List<Integer> newLeftover = new ArrayList<>();
            for (List<Integer> indices : newRemaining.values()) {
                newLeftover.addAll(indices);
            }
            newLeftover.sort(Integer::compareTo);

            if (oldBranches.size() == newBranches.size()) {
                // same size: pair leftover old/new branches positionally
                for (int p = 0; p < Math.min(oldLeftover.size(), newLeftover.size()); p++) {
                    JsonNode oldBranch = oldBranches.get(oldLeftover.get(p));
                    JsonNode newBranch = newBranches.get(newLeftover.get(p));
                    if (oldBranch.isObject() && newBranch.isObject()) {
                        diffSchemaNode(op, path, locationKey, side, oldBranch, newBranch,
                                fieldPath + "[" + oldLeftover.get(p) + "]", out);
                    }
                }
            } else {
                // size changed: leftovers are genuinely added/removed alternatives
                for (int i : oldLeftover) {
                    out.add(new CompatibilityFinding(
                            side == SchemaSide.REQUEST ? "REQ.COMPOSITION_BRANCH_REMOVED"
                                    : "RESP.COMPOSITION_BRANCH_REMOVED",
                            side == SchemaSide.REQUEST ? Severity.BREAKING : Severity.INFO,
                            side.name(), op.verb(), path, locationKey, fieldPath,
                            "'" + key + "' alternative removed",
                            JsonWriter.write(oldBranches.get(i)), null));
                }
                for (int j : newLeftover) {
                    out.add(new CompatibilityFinding(
                            side == SchemaSide.REQUEST ? "REQ.COMPOSITION_BRANCH_ADDED"
                                    : "RESP.COMPOSITION_BRANCH_ADDED",
                            side == SchemaSide.REQUEST ? Severity.INFO : Severity.BREAKING,
                            side.name(), op.verb(), path, locationKey, fieldPath,
                            "'" + key + "' alternative added",
                            null, JsonWriter.write(newBranches.get(j))));
                }
            }
        }
    }

    private static boolean isArray(JsonNode node) {
        return node != null && node.isArray();
    }

    private static List<JsonNode> toArray(JsonNode arrayNode) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            out.add(element);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void emit(LocationKeys.Operation op, String path, String locationKey, SchemaSide side,
                      String fieldPath, String requestCode, Severity requestSeverity,
                      String responseCode, Severity responseSeverity,
                      String detail, String oldJson, String newJson,
                      List<CompatibilityFinding> out) {
        out.add(new CompatibilityFinding(
                side == SchemaSide.REQUEST ? requestCode : responseCode,
                side == SchemaSide.REQUEST ? requestSeverity : responseSeverity,
                side.name(), op.verb(), path, locationKey, fieldPath, detail, oldJson, newJson));
    }

    private static CompatibilityFinding bodyFinding(LocationKeys.Operation op, String path,
                                                    String code, Severity severity, String media,
                                                    String detail, String oldJson, String newJson) {
        return new CompatibilityFinding(code, severity, "REQUEST_BODY", op.verb(), path,
                bodyLocation(op, media), null, detail, oldJson, newJson);
    }

    private static CompatibilityFinding responseFinding(LocationKeys.Operation op, String path,
                                                        String code, Severity severity, String status,
                                                        String media, String detail,
                                                        String oldJson, String newJson) {
        return new CompatibilityFinding(code, severity, "RESPONSE", op.verb(), path,
                responseLocation(op, status, media), null, detail, oldJson, newJson);
    }

    private static String bodyLocation(LocationKeys.Operation op, String media) {
        return op.verb() + " " + op.shape() + " :: requestBody" + (media.isEmpty() ? "" : ":" + media);
    }

    private static String responseLocation(LocationKeys.Operation op, String status, String media) {
        return op.verb() + " " + op.shape() + " :: response:" + status
                + (media == null || media.isEmpty() ? "" : ":" + media);
    }

    private static String childPath(String parent, String name) {
        return parent == null || parent.isEmpty() ? name : parent + "." + name;
    }

    private static Map<String, JsonNode> propertyMap(JsonNode properties) {
        Map<String, JsonNode> map = new TreeMap<>();
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    private static Set<String> requiredSet(JsonNode schema) {
        Set<String> out = new TreeSet<>();
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode element : required) {
                out.add(element.asText());
            }
        }
        return out;
    }

    private static Set<String> typeSet(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null || isNullish(type)) {
            return Set.of();
        }
        if (type.isString()) {
            return Set.of(type.stringValue());
        }
        Set<String> out = new TreeSet<>();
        if (type.isArray()) {
            for (JsonNode element : type) {
                out.add(element.asText());
            }
        }
        return out;
    }

    private static Set<String> enumSet(JsonNode schema) {
        JsonNode enumNode = schema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        for (JsonNode element : enumNode) {
            out.add(element.asText());
        }
        return out;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.removeAll(right);
        return out;
    }

    private static boolean boolValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static boolean isNullish(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static JsonNode parseJson(String json) {
        try {
            return JSON.readTree(json == null ? "null" : json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("compatibility engine received non-JSON effective value: " + json, e);
        }
    }

    /** Shared by all rules: writes a canonical node as its JSON string (or null). */
    private static final class JsonWriter {
        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        static String write(JsonNode node) {
            return node == null || node.isMissingNode() ? null : MAPPER.writeValueAsString(node);
        }
    }
}
