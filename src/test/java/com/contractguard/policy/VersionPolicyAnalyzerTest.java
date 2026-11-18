package com.contractguard.policy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.Severity;
import com.contractguard.policy.PolicyFinding;
import com.contractguard.policy.Semver;
import com.contractguard.policy.VersionPolicyAnalyzer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version-policy channel is tested in isolation: it consumes the compatibility
 * result (as a boolean here) and emits its own findings, which must never gate.
 */
class VersionPolicyAnalyzerTest {

    private final VersionPolicyAnalyzer analyzer = new VersionPolicyAnalyzer();

    @Test
    @DisplayName("semver parsing: valid, 2-part, v-prefix, suffix, invalid")
    void semverParsing() {
        assertThat(Semver.parse("1.0.0")).contains(new Semver(1, 0, 0));
        assertThat(Semver.parse("2.1")).contains(new Semver(2, 1, 0));
        assertThat(Semver.parse("v3.2.1")).contains(new Semver(3, 2, 1));
        assertThat(Semver.parse("1.2.3-beta")).contains(new Semver(1, 2, 3));
        assertThat(Semver.parse("abc")).isEmpty();
        assertThat(Semver.parse("1")).isEmpty();
        assertThat(Semver.parse("1.2.3.4")).isEmpty();
        assertThat(Semver.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("bump classification across components")
    void bumpClassification() {
        assertThat(Semver.bump(new Semver(1, 0, 0), new Semver(1, 0, 0))).isEqualTo(Semver.Bump.NONE);
        assertThat(Semver.bump(new Semver(1, 0, 0), new Semver(1, 0, 1))).isEqualTo(Semver.Bump.PATCH);
        assertThat(Semver.bump(new Semver(1, 0, 9), new Semver(1, 1, 0))).isEqualTo(Semver.Bump.MINOR);
        assertThat(Semver.bump(new Semver(1, 9, 9), new Semver(2, 0, 0))).isEqualTo(Semver.Bump.MAJOR);
    }

    @Test
    @DisplayName("breaking change under a MINOR bump is flagged (warn) — the planted violation pattern")
    void breakingWithoutMajor() {
        List<PolicyFinding> findings = analyzer.analyze("2.0.0", "2.1.0", true);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("VERSION.POLICY.BREAKING_WITHOUT_MAJOR");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(findings.get(0).detail()).contains("2.0.0").contains("2.1.0").contains("minor");
    }

    @Test
    @DisplayName("breaking change under a MAJOR bump is NOT flagged")
    void breakingWithMajorIsClean() {
        assertThat(analyzer.analyze("1.1.0", "2.0.0", true)).isEmpty();
        assertThat(analyzer.analyze("2.0.0", "3.0.0", true)).isEmpty();
    }

    @Test
    @DisplayName("no breaking change under minor/patch is clean")
    void benignBumpsAreClean() {
        assertThat(analyzer.analyze("1.0.0", "1.1.0", false)).isEmpty();
        assertThat(analyzer.analyze("1.1.0", "1.1.1", false)).isEmpty();
    }

    @Test
    @DisplayName("major bump without breaking changes is informational")
    void majorWithoutBreaking() {
        List<PolicyFinding> findings = analyzer.analyze("1.0.0", "2.0.0", false);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("VERSION.POLICY.MAJOR_WITHOUT_BREAKING");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("unparseable versions produce a skip finding, never a crash")
    void unparseable() {
        List<PolicyFinding> findings = analyzer.analyze("release-7", "1.0.0", true);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("VERSION.UNPARSEABLE");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("first publication (no old version) has nothing to audit")
    void firstPublication() {
        assertThat(analyzer.analyze(null, "1.0.0", true)).isEmpty();
        assertThat(analyzer.analyze(null, "1.0.0", false)).isEmpty();
    }

    @Test
    @DisplayName("same-version repost with breaking findings counts as a non-major bump")
    void sameVersionBreaking() {
        List<PolicyFinding> findings = analyzer.analyze("1.0.0", "1.0.0", true);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("VERSION.POLICY.BREAKING_WITHOUT_MAJOR");
        assertThat(findings.get(0).detail()).contains("no component");
    }
}
