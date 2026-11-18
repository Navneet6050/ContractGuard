package com.contractguard.policy;

import java.util.Optional;

/**
 * Minimal semver value object for the version-policy channel. Accepts "X.Y.Z" and
 * "X.Y" (patch defaults to 0), optional leading "v", optional "-suffix" ignored.
 * Never used to decide compatibility — only to AUDIT whether the bump type matches
 * the actual change profile (PLAN.md policy rule 5).
 */
public record Semver(long major, long minor, long patch) implements Comparable<Semver> {

    public static Optional<Semver> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        int suffix = cleaned.indexOf('-');
        if (suffix >= 0) {
            cleaned = cleaned.substring(0, suffix);
        }
        String[] parts = cleaned.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return Optional.empty();
        }
        try {
            long major = Long.parseLong(parts[0]);
            long minor = Long.parseLong(parts[1]);
            long patch = parts.length == 3 ? Long.parseLong(parts[2]) : 0L;
            if (major < 0 || minor < 0 || patch < 0) {
                return Optional.empty();
            }
            return Optional.of(new Semver(major, minor, patch));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public enum Bump { NONE, PATCH, MINOR, MAJOR }

    public static Bump bump(Semver oldVersion, Semver newVersion) {
        if (newVersion.major != oldVersion.major) {
            return Bump.MAJOR;
        }
        if (newVersion.minor != oldVersion.minor) {
            return Bump.MINOR;
        }
        if (newVersion.patch != oldVersion.patch) {
            return Bump.PATCH;
        }
        return Bump.NONE;
    }

    @Override
    public int compareTo(Semver other) {
        int byMajor = Long.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Long.compare(minor, other.minor);
        if (byMinor != 0) {
            return byMinor;
        }
        return Long.compare(patch, other.patch);
    }
}
