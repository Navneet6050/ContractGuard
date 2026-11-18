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
            "pattern", "multipleOf", "exclusiveMinimum", "exclusiveMaximum", "uniqueItems");

    /** +1 = new value is tighter, -1 = looser, 0 = no directional change. */
    private static int tightness(String key, JsonNode oldValue, JsonNode newValue) {
        return switch (key) {
            case "minimum", "minLength", "minItems" -> Double.compare(num(newValue), num(oldValue));
            case "maximum", "maxLength", "maxItems" -> Double.compare(num(oldValue), num(newValue));
            case "pattern", "multipleOf" -> 1;
            case "exclusiveMinimum", "exclusiveMaximum", "uniqueItems" ->
                    newValue.booleanValue() && !oldValue.booleanValue() ? 1
                            : oldValue.booleanValue() && !newValue.booleanValue() ? -1 : 0;
            default -> 0;
        };
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
            out.add(new CompatibilityFinding("REQ.SCHEMA_ADDED", Severity.INFO, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter schema added", null, JsonWriter.write(newSchema)));
        } else if (!isNullish(oldSchema) && isNullish(newSchema)) {
            out.add(new CompatibilityFinding("REQ.SCHEMA_REMOVED", Severity.BREAKING, "PARAM",
                    op.verb(), path, locationKey, null,
                    "parameter schema removed", JsonWriter.write(oldSchema), null));
        } else if (!isNullish(oldSchema)) {
            diffSchemaNode(op, path, locationKey, SchemaSide.REQUEST, oldSchema, newSchema, "", out);
        }
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
            out.add(new CompatibilityFinding(side.prefix() + ".SCHEMA_ADDED", Severity.INFO,
                    side.name(), op.verb(), path, locationKey, fieldPath,
                    "schema added where none was documented", null, JsonWriter.write(newNode)));
            return;
        }
        if (newNull) {
            out.add(new CompatibilityFinding(side.prefix() + ".SCHEMA_REMOVED", Severity.BREAKING,
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
                direction = 1;
                verb = "added";
            } else {
                direction = -1;
                verb = "removed";
            }
            if (direction == 0) {
                continue;
            }
            String detail = "'" + key + "' " + verb
                    + (newPresent ? ": " + JsonWriter.write(newValue) : "")
                    + (oldPresent && newPresent ? " (was " + JsonWriter.write(oldValue) + ")" : "");
            // Directional codes, mirroring the enum/type rules: tightening breaks senders
            // (request), loosening breaks strict readers (response).
            boolean tightened = direction > 0;
            boolean request = side == SchemaSide.REQUEST;
            out.add(new CompatibilityFinding(
                    request ? (tightened ? "REQ.CONSTRAINT_TIGHTENED" : "REQ.CONSTRAINT_LOOSENED")
                            : (tightened ? "RESP.CONSTRAINT_TIGHTENED" : "RESP.CONSTRAINT_LOOSENED"),
                    tightened == request ? Severity.BREAKING : Severity.INFO,
                    side.name(), op.verb(), path, locationKey, fieldPath, detail,
                    JsonWriter.write(oldValue), JsonWriter.write(newValue)));
        }
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
            emit(op, path, locationKey, side, childPath(fieldPath, rename.getKey()),
                    "REQ.PROPERTY_RENAMED", Severity.BREAKING, "RESP.PROPERTY_RENAMED", Severity.BREAKING,
                    "property '" + rename.getKey() + "' renamed to '" + rename.getValue()
                            + "' (same shape)",
                    JsonWriter.write(oldMap.get(rename.getKey())),
                    JsonWriter.write(newMap.get(rename.getValue())), out);
        }
        for (String name : removed) {
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.PROPERTY_REMOVED", Severity.BREAKING, "RESP.PROPERTY_REMOVED", Severity.BREAKING,
                    "property '" + name + "' removed",
                    JsonWriter.write(oldMap.get(name)), null, out);
        }
        for (String name : added) {
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.PROPERTY_ADDED", Severity.INFO, "RESP.PROPERTY_ADDED", Severity.INFO,
                    "property '" + name + "' added",
                    null, JsonWriter.write(newMap.get(name)), out);
        }

        // recurse into common properties
        for (String name : oldMap.keySet()) {
            JsonNode newChild = newMap.get(name);
            if (newChild != null) {
                diffSchemaNode(op, path, locationKey, side, oldMap.get(name), newChild,
                        childPath(fieldPath, name), out);
            }
        }

        compareRequired(op, path, locationKey, side, oldNode, newNode, fieldPath, renames, out);
    }

    private void compareRequired(LocationKeys.Operation op, String path, String locationKey,
                                 SchemaSide side, JsonNode oldNode, JsonNode newNode,
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
            emit(op, path, locationKey, side, childPath(fieldPath, name),
                    "REQ.REQUIRED_ADDED", Severity.BREAKING, "RESP.REQUIRED_ADDED", Severity.INFO,
                    "'" + name + "' added to required list"
                            + (side == SchemaSide.REQUEST ? "" : " (guarantee strengthened)"),
                    null, null, out);
        }
        for (String name : difference(oldRequired, newRequired)) {
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
        if (oldItems != null && oldItems.isObject() && newItems != null && newItems.isObject()) {
            diffSchemaNode(op, path, locationKey, side, oldItems, newItems,
                    fieldPath + "[]", out);
        }
    }

    private void compareCompositions(LocationKeys.Operation op, String path, String locationKey,
                                     SchemaSide side, JsonNode oldNode, JsonNode newNode,
                                     String fieldPath, List<CompatibilityFinding> out) {
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode oldList = oldNode.get(key);
            JsonNode newList = newNode.get(key);
            if (oldList == null && newList == null) {
                continue;
            }
            if (oldList != null && oldList.isArray() && newList != null && newList.isArray()
                    && oldList.size() == newList.size()) {
                for (int i = 0; i < oldList.size(); i++) {
                    JsonNode oldElement = oldList.get(i);
                    JsonNode newElement = newList.get(i);
                    if (oldElement.isObject() && newElement.isObject()) {
                        diffSchemaNode(op, path, locationKey, side, oldElement, newElement,
                                fieldPath + "[" + i + "]", out);
                    }
                }
            } else if (oldList != null && newList != null) {
                out.add(new CompatibilityFinding(side.prefix() + ".COMPOSITION_CHANGED", Severity.INFO,
                        side.name(), op.verb(), path, locationKey, fieldPath,
                        "'" + key + "' composition list changed", null, null));
            }
        }
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
