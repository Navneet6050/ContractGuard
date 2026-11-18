package com.contractguard.impact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.LocationKeys;
import com.contractguard.compatibility.Severity;
import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.DeclaredOperation;
import com.contractguard.specs.SpecDocument;

/**
 * Matches compatibility findings against declared consumer usage (PLAN.md §7).
 *
 * Matching is strictly structured — no substring matching anywhere:
 * - bindings join findings by service slug + HTTP method + path SHAPE (placeholder
 *   names are normalized by the shared specs module, so {@code /quotes/{id}} matches
 *   {@code /quotes/{quoteId}});
 * - request-side findings join {@code sends}, response-side findings join {@code reads};
 * - field paths compare as SEGMENT LISTS (dots and {@code []} items), overlapping when
 *   one path is a segment-prefix of the other ({@code premium} overlaps
 *   {@code premium.breakdown[].amount}; sibling {@code premium.total} does not);
 * - renamed properties are matched against BOTH the old path (finding.fieldPath) and
 *   the new path (derived from the finding detail);
 * - parameter findings are resolved to names via the specs module's param-name index
 *   (old spec for removals, new spec for additions/required flips).
 *
 * Tier semantics (PLAN.md §6/§7 + policy):
 * - AFFECTED: declared usage directly overlaps a breaking change (or the changed
 *   element is operation-wide), or a now-required element is provably not sent.
 * - POSSIBLE: the consumer uses the affected operation but its declarations cannot
 *   prove or rule out dependence; also the advisory tier for response-side widening
 *   (new enum/type values) hit by declared readers — files cannot express exhaustive
 *   value matching.
 * - NONE: independence is provable (declared usage excludes the changed element) or
 *   nothing declared is relevant. Non-breaking findings never rise above NONE.
 */
public class ConsumerImpactAnalyzer {

    /**
     * Breaking response-side findings whose damage depends on the consumer's value
     * handling, which files cannot declare → declared readers land in POSSIBLE.
     */
    private static final Set<String> WIDENING_CODES = Set.of(
            "RESP.ENUM_WIDENED", "RESP.TYPE_WIDENED", "RESP.CONSTRAINT_LOOSENED", "RESP.NULLABLE_ADDED",
            "RESP.COMPOSITION_BRANCH_ADDED");

    private static final Pattern RENAME_IN_DETAIL = Pattern.compile("'(.+?)' renamed to '(.+?)'");

    /**
     * @param service       provider service slug the findings belong to (e.g. "quote-api")
     * @param oldDoc        previously published spec (param-name index for removals)
     * @param newDoc        candidate spec (param-name index for additions/required flips)
     * @param findings      compatibility findings old→new (engine output, any order)
     * @param consumers     all registered consumers
     */
    public ImpactSummary analyze(String service, SpecDocument oldDoc, SpecDocument newDoc,
                                 List<CompatibilityFinding> findings,
                                 List<ConsumerDeclaration> consumers) {
        List<ConsumerImpact> perConsumer = consumers.stream()
                .sorted(java.util.Comparator.comparing(ConsumerDeclaration::name))
                .map(consumer -> analyzeConsumer(service, oldDoc, newDoc, findings, consumer))
                .toList();

        int affected = 0;
        int possible = 0;
        for (ConsumerImpact impact : perConsumer) {
            switch (impact.level()) {
                case AFFECTED -> affected++;
                case POSSIBLE -> possible++;
                default -> { /* NONE */ }
            }
        }
        return new ImpactSummary(service, oldDoc.getSpecVersion(), newDoc.getSpecVersion(),
                perConsumer, affected, possible, perConsumer.size() - affected - possible);
    }

    private ConsumerImpact analyzeConsumer(String service, SpecDocument oldDoc, SpecDocument newDoc,
                                           List<CompatibilityFinding> findings,
                                           ConsumerDeclaration consumer) {
        List<DeclaredOperation> bindings = consumer.forProvider(service);
        List<String> operations = bindings.stream().map(DeclaredOperation::display).sorted().toList();

        Set<FindingImpact> unique = new LinkedHashSet<>();
        for (CompatibilityFinding finding : findings) {
            for (DeclaredOperation binding : bindings) {
                classify(service, oldDoc, newDoc, finding, binding).ifPresent(unique::add);
            }
        }
        List<FindingImpact> impacts = List.copyOf(unique);
        ImpactLevel level = impacts.stream()
                .map(FindingImpact::level)
                .reduce(ImpactLevel::worst)
                .orElse(ImpactLevel.NONE);

        String reason;
        if (impacts.isEmpty()) {
            reason = operations.isEmpty()
                    ? "declares no usage of '" + service + "'"
                    : "declared usage of '" + service + "' (" + String.join(", ", operations)
                            + ") is not touched by any compatibility finding";
        } else {
            reason = switch (level) {
                case AFFECTED -> "declared usage directly overlaps breaking change(s)";
                case POSSIBLE -> "declared usage may depend on changed element(s) - see findings";
                case NONE -> "no breaking impact on declared usage";
            };
        }
        return new ConsumerImpact(consumer.name(), consumer.team(), level, operations, impacts, reason);
    }

    // ------------------------------------------------------------------ classification

    private Optional<FindingImpact> classify(String service, SpecDocument oldDoc, SpecDocument newDoc,
                                             CompatibilityFinding f, DeclaredOperation binding) {
        // Structured join: same service slug, same method, same path shape. Never substring.
        LocationKeys.Operation op = LocationKeys.operationOf(f.location());
        if (!binding.provider().equals(service)
                || !binding.method().equals(f.method())
                || !binding.pathShape().equals(op.shape())) {
            return Optional.empty();
        }
        boolean breaking = f.severity() == Severity.BREAKING;
        if ("CONSUMERS".equalsIgnoreCase(f.scope())) {
            return Optional.of(driftImpact(f, binding));
        }
        if (!breaking) {
            return Optional.of(none(f, "informational change (" + f.detail()
                    + ") - non-breaking, no consumer impact"));
        }
        return switch (f.scope()) {
            case "OPERATION" -> Optional.of(affected(f,
                    "operation " + f.method() + " " + f.path() + " that you call was removed"));
            case "REQUEST_BODY" -> Optional.of(affected(f,
                    "the request contract of " + f.method() + " " + f.path() + " changed: " + f.detail()));
            case "PARAM" -> Optional.of(paramImpact(f, binding, oldDoc, newDoc));
            case "REQUEST" -> Optional.of(requestImpact(f, binding));
            case "RESPONSE" -> Optional.of(responseImpact(f, binding));
            default -> Optional.of(possible(f, "unhandled finding scope '" + f.scope()
                    + "' - manual review required"));
        };
    }

    // ------------------------------------------------------------------ consumer-drift findings

    /**
     * Findings from the {@link ConsumerDriftScanner} (scope {@code consumers:*}) are
     * advisory by design: they name declarations that no longer match the incoming spec,
     * which may be accepted history rather than damage from this candidate. The declared
     * overlap proves the consumer still references the drifted element → POSSIBLE.
     */
    private FindingImpact driftImpact(CompatibilityFinding f, DeclaredOperation binding) {
        if (f.fieldPath() == null || f.fieldPath().isBlank()) {
            return possible(f, "you declare " + binding.display()
                    + ", but the incoming spec contains no matching operation"
                    + " (update or re-upload the consumer declaration)");
        }
        // the reads/sends kind is encoded in the finding's location (scope is CONSUMERS)
        boolean reads = f.location() != null && f.location().contains("consumers:reads");
        List<String> declared = reads ? binding.reads() : binding.sends();
        String overlap = firstOverlap(declared, f.fieldPath(), null);
        String kind = reads ? "read" : "send";
        if (overlap != null) {
            return possible(f, "you " + kind + " '" + overlap + "' on " + f.method() + " "
                    + f.path() + ", which the incoming spec no longer documents"
                    + " (declaration predates the current contract)");
        }
        return none(f, "no declared " + kind + " of '" + f.fieldPath() + "' on "
                + f.method() + " " + f.path() + " — this drift concerns other consumers");
    }

    // ------------------------------------------------------------------ parameters

    private FindingImpact paramImpact(CompatibilityFinding f, DeclaredOperation binding,
                                      SpecDocument oldDoc, SpecDocument newDoc) {
        // The engine's envelopes deliberately drop parameter names; the specs module's
        // name index restores them (old spec for removals, new spec for additions).
        String oldName = oldDoc.getParamNames().get(f.location());
        String newName = newDoc.getParamNames().get(f.location());
        boolean added = f.code().equals("PARAM.REQUIRED_ADDED");
        String name = added ? (newName != null ? newName : oldName)
                : (oldName != null ? oldName : newName);
        if (name == null) {
            return possible(f, "parameter at '" + f.location() + "' changed ("
                    + f.detail() + ") but its name cannot be resolved from the specifications;"
                    + " whether you send it is unknown");
        }
        boolean declared = binding.sends().contains(name);
        if (added) {
            if (declared) {
                return none(f, "already declares sending parameter '" + name + "'");
            }
            return affected(f, "'" + name + "' is now required on " + f.method() + " " + f.path()
                    + " but is not declared in sends");
        }
        // PARAM.REMOVED
        if (declared) {
            return affected(f, "you send parameter '" + name + "', which was removed from "
                    + f.method() + " " + f.path());
        }
        return none(f, "does not declare sending parameter '" + name + "'");
    }

    // ------------------------------------------------------------------ request side

    private FindingImpact requestImpact(CompatibilityFinding f, DeclaredOperation binding) {
        String field = f.fieldPath();
        if (field == null || field.isBlank()) {
            return affected(f, "the documented request schema of " + f.method() + " " + f.path()
                    + " changed: " + f.detail());
        }
        if (f.code().equals("REQ.REQUIRED_ADDED")) {
            return requiredAddedImpact(f, binding, field);
        }
        String newPath = renameNewPath(f);
        String overlapPath = firstOverlap(binding.sends(), field, newPath);
        if (overlapPath != null) {
            String caveat = f.code().equals("REQ.ENUM_NARROWED")
                    ? " (sent values are not recorded in the consumer file, so a sent value may be"
                            + " one of the removed ones: " + f.detail() + ")"
                    : "";
            return affected(f, "you send '" + overlapPath + "' on " + f.method() + " " + f.path()
                    + ", which " + codePhrase(f.code(), field, newPath) + caveat);
        }
        if (binding.sends().isEmpty()) {
            return possible(f, "you call " + f.method() + " " + f.path() + " but declare no request"
                    + " fields; dependence on '" + field + "' cannot be ruled out");
        }
        return none(f, "declared sends on " + f.method() + " " + f.path() + " do not include '"
                + field + "'");
    }

    /**
     * REQ.REQUIRED_ADDED inverts the usual overlap logic: declaring the field proves
     * compliance (NONE); declaring only a containing object is genuinely unknown
     * (POSSIBLE); proving the field is NOT sent while it becomes mandatory is AFFECTED.
     */
    private FindingImpact requiredAddedImpact(CompatibilityFinding f, DeclaredOperation binding,
                                              String field) {
        // Declaring the field itself (or a subfield of it — sending a subfield implies
        // sending its parent object) proves the required element is already sent.
        for (String send : binding.sends()) {
            if (isPrefixOrEqual(segments(field), segments(send))) {
                return none(f, "already declares sending '" + send + "'");
            }
        }
        // Declaring only a containing object: whether it includes the field is unknown.
        for (String send : binding.sends()) {
            if (isPrefixOrEqual(segments(send), segments(field))) {
                return possible(f, "you declare sending '" + send + "', which may or may not include"
                        + " the now-required '" + field + "'");
            }
        }
        return affected(f, "'" + field + "' is now required on " + f.method() + " " + f.path()
                + " but is not declared in sends");
    }

    // ------------------------------------------------------------------ response side

    private FindingImpact responseImpact(CompatibilityFinding f, DeclaredOperation binding) {
        String field = f.fieldPath();
        if (field == null || field.isBlank()) {
            return affected(f, "the documented response of " + f.method() + " " + f.path()
                    + " changed: " + f.detail());
        }
        String newPath = renameNewPath(f);
        String overlapPath = firstOverlap(binding.reads(), field, newPath);
        if (overlapPath != null) {
            if (WIDENING_CODES.contains(f.code())) {
                return possible(f, "you read '" + overlapPath + "' on " + f.method() + " " + f.path()
                        + "; the server may return values your matching does not handle ("
                        + f.detail() + ")");
            }
            return affected(f, "you read '" + overlapPath + "' on " + f.method() + " " + f.path()
                    + ", which " + codePhrase(f.code(), field, newPath));
        }
        if (binding.reads().isEmpty()) {
            return possible(f, "you call " + f.method() + " " + f.path() + " but declare no response"
                    + " fields; dependence on '" + field + "' cannot be ruled out");
        }
        return none(f, "declared reads on " + f.method() + " " + f.path() + " do not include '"
                + field + "'");
    }

    // ------------------------------------------------------------------ path helpers

    /**
     * First declared path overlapping the finding's old or new path: overlap means one
     * segment list is a prefix of the other ({@code []} markers are equated with their
     * parent segment, so an array and its items belong to one lineage). Returns the
     * DECLARED path for use in the reason text, or null.
     */
    private static String firstOverlap(List<String> declared, String oldPath, String newPath) {
        for (String candidate : declared) {
            if (overlaps(candidate, oldPath) || (newPath != null && overlaps(candidate, newPath))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean overlaps(String a, String b) {
        return isPrefixOrEqual(segments(a), segments(b)) || isPrefixOrEqual(segments(b), segments(a));
    }

    private static boolean isPrefixOrEqual(List<String> prefix, List<String> longer) {
        if (prefix.size() > longer.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(longer.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** "premium.breakdown[].amount" → ["premium", "breakdown", "amount"] ([] markers equated). */
    private static List<String> segments(String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            out.add(part.endsWith("[]") ? part.substring(0, part.length() - 2) : part);
        }
        return out;
    }

    /** For PROPERTY_RENAMED findings: fieldPath with its last segment replaced by the new name. */
    private static String renameNewPath(CompatibilityFinding f) {
        if (f.fieldPath() == null || !f.code().endsWith("PROPERTY_RENAMED")) {
            return null;
        }
        Matcher m = RENAME_IN_DETAIL.matcher(f.detail() == null ? "" : f.detail());
        if (!m.find()) {
            return null;
        }
        int lastDot = f.fieldPath().lastIndexOf('.');
        return lastDot < 0 ? m.group(2) : f.fieldPath().substring(0, lastDot + 1) + m.group(2);
    }

    /** Short per-code verb phrase for reason texts. */
    private static String codePhrase(String code, String field, String newPath) {
        return switch (code) {
            case "RESP.PROPERTY_REMOVED" -> "was removed from responses";
            case "REQ.PROPERTY_REMOVED" -> "was removed from the accepted requests";
            case "RESP.REQUIRED_REMOVED" -> "is no longer guaranteed in responses";
            case "REQ.REQUIRED_REMOVED" -> "is no longer required in requests";
            case "RESP.PROPERTY_RENAMED", "REQ.PROPERTY_RENAMED" ->
                    "is renamed to '" + (newPath != null
                            ? newPath.substring(newPath.lastIndexOf('.') + 1) : "?") + "'";
            case "REQ.ENUM_NARROWED" -> "had accepted enum value(s) removed";
            case "REQ.TYPE_NARROWED", "REQ.CONSTRAINT_TIGHTENED" -> "was tightened";
            case "RESP.TYPE_CHANGED" -> "changed type";
            default -> "changed (" + code + ")";
        };
    }

    // ------------------------------------------------------------------ impact factories

    private static FindingImpact affected(CompatibilityFinding f, String reason) {
        return impact(f, ImpactLevel.AFFECTED, reason);
    }

    private static FindingImpact possible(CompatibilityFinding f, String reason) {
        return impact(f, ImpactLevel.POSSIBLE, reason);
    }

    private static FindingImpact none(CompatibilityFinding f, String reason) {
        return impact(f, ImpactLevel.NONE, reason);
    }

    private static FindingImpact impact(CompatibilityFinding f, ImpactLevel level, String reason) {
        return new FindingImpact(f.code(), f.severity().code(), f.method(), f.path(),
                f.location(), f.fieldPath(), level, reason);
    }
}
