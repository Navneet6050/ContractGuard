package com.contractguard.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.contractguard.compatibility.Severity;

/**
 * Audits whether the version bump matches the actual change profile. Takes the
 * compatibility result as an INPUT (hasBreakingFindings) — it never re-diffs specs,
 * and its findings never merge into the compatibility channel.
 */
public final class VersionPolicyAnalyzer {

    /**
     * @param oldVersion previously published info.version (null on first publication)
     * @param newVersion incoming info.version
     * @param hasBreakingFindings true when the compatibility engine produced ≥1 breaking finding
     */
    public List<PolicyFinding> analyze(String oldVersion, String newVersion, boolean hasBreakingFindings) {
        List<PolicyFinding> out = new ArrayList<>();
        if (oldVersion == null) {
            return out; // first publication: nothing to compare the bump against
        }
        Optional<Semver> oldSemver = Semver.parse(oldVersion);
        Optional<Semver> newSemver = Semver.parse(newVersion);
        if (oldSemver.isEmpty() || newSemver.isEmpty()) {
            out.add(new PolicyFinding("VERSION.UNPARSEABLE", Severity.INFO,
                    "cannot assess semver policy (old='" + oldVersion + "', new='" + newVersion + "')"));
            return out;
        }
        Semver.Bump bump = Semver.bump(oldSemver.get(), newSemver.get());
        if (hasBreakingFindings && bump != Semver.Bump.MAJOR) {
            out.add(new PolicyFinding("VERSION.POLICY.BREAKING_WITHOUT_MAJOR", Severity.WARN,
                    "breaking changes detected but version bumped "
                            + bumpName(bump) + " (" + oldVersion + " -> " + newVersion
                            + "); a major bump is required by policy"));
        }
        if (!hasBreakingFindings && bump == Semver.Bump.MAJOR) {
            out.add(new PolicyFinding("VERSION.POLICY.MAJOR_WITHOUT_BREAKING", Severity.INFO,
                    "major version bump (" + oldVersion + " -> " + newVersion
                            + ") without detected breaking changes"));
        }
        return out;
    }

    private static String bumpName(Semver.Bump bump) {
        return switch (bump) {
            case MAJOR -> "major";
            case MINOR -> "minor";
            case PATCH -> "patch";
            case NONE -> "no component";
        };
    }
}
