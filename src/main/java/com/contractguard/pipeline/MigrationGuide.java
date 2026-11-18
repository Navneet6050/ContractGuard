package com.contractguard.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.contractguard.persistence.RegistryStore;

/**
 * Migration-guide generation from ACTUAL stored compatibility findings (no fabrication):
 * every step quotes a persisted finding's location/field and maps the finding code to a
 * concrete, grounded action for the teams that call the changed operation. Consumer
 * names come from the stored impact rows, so each step says WHO has to act.
 *
 * <p>Codes without a specific template fall back to a review step quoting the finding's
 * own detail text — a step is never invented beyond what the finding states. Parameter
 * names (deliberately absent from finding envelopes) are restored from the spec's
 * parameter-name index when available.
 */
public final class MigrationGuide {

    /** One grounded migration step. */
    public record Step(String code, String severity, String location, String fieldPath,
                       String action, List<String> consumers) {

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            out.put("severity", severity);
            out.put("location", location == null ? "" : location);
            out.put("fieldPath", fieldPath == null ? "" : fieldPath);
            out.put("action", action);
            out.put("consumers", consumers);
            return out;
        }
    }

    private MigrationGuide() {
    }

    /**
     * @param findings         persisted findings of one attempt, in stored order
     * @param impactsByFinding stored impact rows keyed by finding id
     * @param paramNames       location → parameter-name index of the attempt's spec
     *                         (may be empty; used only to name parameters in actions)
     */
    public static List<Step> build(List<RegistryStore.StoredFinding> findings,
                                   Map<Long, List<RegistryStore.StoredImpact>> impactsByFinding,
                                   Map<String, String> paramNames) {
        List<Step> steps = new ArrayList<>();
        for (RegistryStore.StoredFinding finding : findings) {
            String field = finding.fieldPath();
            String where = finding.location() == null ? "" : finding.location();
            String param = paramName(finding, paramNames);
            String action = switch (finding.code()) {
                case "RESP.PROPERTY_RENAMED" -> fielded(
                        "Update readers: the response field was renamed (see detail) — read the"
                                + " new name and remove uses of the old one.",
                        field, "renamed", finding.detail());
                case "RESP.PROPERTY_REMOVED" -> fielded(
                        "Stop reading it; it is no longer part of responses. Handle its absence"
                                + " explicitly.",
                        field, "removed from responses", finding.detail());
                case "RESP.REQUIRED_REMOVED" -> fielded(
                        "Do not rely on it always being present in responses; it is no longer a"
                                + " guaranteed field.",
                        field, "no longer guaranteed", finding.detail());
                case "RESP.ENUM_WIDENED", "RESP.TYPE_WIDENED", "RESP.CONSTRAINT_LOOSENED",
                     "RESP.NULLABLE_ADDED" -> fielded(
                        "Extend handling in readers to cover the widened response space"
                                + " (new values/shapes): " + finding.detail(),
                        field, "response space widened", null);
                case "RESP.RESPONSE_REMOVED", "RESP.MEDIA_TYPE_REMOVED" -> plain(
                        "Stop expecting this response/media type: " + finding.detail(), field);
                case "REQ.REQUIRED_ADDED" -> fielded(
                        "Always send it on requests to this operation; requests without it will"
                                + " be rejected.",
                        field, "now required in requests", finding.detail());
                case "REQ.PROPERTY_REMOVED" -> fielded(
                        "Stop sending it; the server no longer accepts it.",
                        field, "removed from requests", finding.detail());
                case "REQ.ENUM_NARROWED" -> fielded(
                        "Stop sending value(s) that are no longer accepted (" + finding.detail()
                                + ").",
                        field, "accepted values narrowed", null);
                case "REQ.TYPE_NARROWED", "REQ.CONSTRAINT_TIGHTENED" -> fielded(
                        "Conform sent values to the tightened contract: " + finding.detail(),
                        field, "request contract tightened", null);
                case "PARAM.REQUIRED_ADDED" -> param != null
                        ? plain("Always send the '" + param + "' parameter on this operation;"
                                + " requests without it will be rejected.", field)
                        : plain("Send the newly required parameter on this operation (see"
                                + " finding detail).", field);
                case "PARAM.REMOVED" -> param != null
                        ? plain("Stop sending the '" + param + "' parameter; it was removed.", field)
                        : plain("Stop sending the removed parameter (see finding detail).", field);
                case "PARAM.SERIALIZATION_CHANGED" -> param != null
                        ? plain("Regenerate or update the client for the '" + param
                                + "' parameter: its wire serialization changed ("
                                + finding.detail() + ").", field)
                        : plain("Regenerate or update the client: this parameter's wire"
                                + " serialization changed (see finding detail).", field);
                case "PATH_SHAPE.CHANGED" -> plain(
                        "Update client routing/templating: the path shape of this operation"
                                + " changed (" + finding.detail() + ").", field);
                case "OPERATION.REMOVED", "PATH.REMOVED" -> plain(
                        "Migrate callers off this operation; it no longer exists.", field);
                case "REQUEST_BODY.MADE_REQUIRED" -> plain(
                        "Requests to this operation must now include a body.", field);
                case "DEPRECATION.ADDED" -> fielded(
                        "Plan migration off it before its sunset date; it will be removed.",
                        field, "newly deprecated", finding.detail());
                case "SUNSET_PASSED" -> fielded(
                        "Remove remaining uses — the documented sunset date has passed while"
                                + " the field is still present.",
                        field, "past its sunset date", finding.detail());
                case "SUNSET_SCHEDULED" -> fielded(
                        "Track the planned removal (" + finding.detail() + ") and prepare"
                                + " consumers.",
                        field, "sunset scheduled", null);
                case "CONSUMER.DRIFT" -> fielded(
                        "Update the consumer declarations named below: the declared usage no"
                                + " longer matches the incoming spec (" + finding.detail() + ").",
                        field, "consumer declaration drift", null);
                case "CONSUMER.STALE_BINDING" -> plain(
                        "Update the consumer declarations named below: the declared operation"
                                + " no longer exists in the incoming spec.", field);
                default -> plain("Review this change: " + finding.detail(), field);
            };
            steps.add(new Step(finding.code(), finding.severity(), where, field, action,
                    consumersOf(finding, impactsByFinding)));
        }
        return steps;
    }

    /** Appends the field reference (and the original finding detail when provided). */
    private static String fielded(String action, String field, String label, String detail) {
        StringBuilder out = new StringBuilder(action);
        if (field != null && !field.isBlank()) {
            out.append(" (field: '").append(field).append("' — ").append(label).append(')');
        }
        if (detail != null && !detail.isBlank() && !action.contains(detail)) {
            out.append(" [finding: ").append(detail).append(']');
        }
        return out.toString();
    }

    private static String plain(String action, String field) {
        if (field == null || field.isBlank()) {
            return action;
        }
        return action + " (field: '" + field + "')";
    }

    /** Parameter name restored from the spec's name index (finding envelopes omit it). */
    private static String paramName(RegistryStore.StoredFinding finding, Map<String, String> paramNames) {
        if (paramNames.isEmpty() || finding.location() == null
                || !finding.location().contains(" :: param:")) {
            return null;
        }
        return paramNames.get(finding.location());
    }

    private static List<String> consumersOf(RegistryStore.StoredFinding finding,
                                            Map<Long, List<RegistryStore.StoredImpact>> impactsByFinding) {
        List<String> out = new ArrayList<>();
        for (RegistryStore.StoredImpact impact : impactsByFinding.getOrDefault(finding.id(), List.of())) {
            String entry = impact.consumer() + " (" + impact.level().toUpperCase() + ")";
            if (!out.contains(entry)) {
                out.add(entry);
            }
        }
        return out;
    }
}
