package com.contractguard.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.persistence.RegistryStore;

/**
 * The migration guide is generated strictly from stored findings: every action quotes
 * the finding's own location/field/detail, consumers come from stored impact rows, and
 * parameter names are restored from the spec's name index. No step is fabricated.
 */
class MigrationGuideTest {

    private static RegistryStore.StoredFinding finding(long id, String code, String severity,
                                                       String method, String location,
                                                       String fieldPath, String detail) {
        return new RegistryStore.StoredFinding(id, code, severity, "SCOPE", method,
                "/things", location, fieldPath, detail, null, null);
    }

    @Test
    @DisplayName("every supported finding code maps to a grounded action naming the field")
    void actionsAreGroundedInTheFinding() {
        List<RegistryStore.StoredFinding> findings = List.of(
                finding(1, "RESP.PROPERTY_RENAMED", "breaking", "GET",
                        "GET /quotes/{0} :: response:200:application/json",
                        "premium.breakdown[].amount", "'premium.breakdown[].amount' renamed to"
                                + " 'premium.breakdown[].value'"),
                finding(2, "REQ.REQUIRED_ADDED", "breaking", "POST",
                        "POST /quotes :: requestBody:application/json", "region",
                        "'region' entered the required set"),
                finding(3, "PARAM.REQUIRED_ADDED", "breaking", "DELETE",
                        "DELETE /cache :: param:query:1", null,
                        "parameter became required"));
        Map<Long, List<RegistryStore.StoredImpact>> impacts = Map.of(
                1L, List.of(new RegistryStore.StoredImpact(1L, "mobile-app", "affected",
                        "you read 'premium.breakdown[].amount'")),
                3L, List.of(new RegistryStore.StoredImpact(3L, "payments-svc", "possible",
                        "parameter dependence unknown")));

        List<MigrationGuide.Step> steps = MigrationGuide.build(findings, impacts, Map.of(
                "DELETE /cache :: param:query:1", "region"));

        assertThat(steps).hasSize(3);
        MigrationGuide.Step rename = steps.get(0);
        assertThat(rename.code()).isEqualTo("RESP.PROPERTY_RENAMED");
        assertThat(rename.action()).contains("premium.breakdown[].amount").contains("renamed");
        assertThat(rename.consumers()).containsExactly("mobile-app (AFFECTED)");

        MigrationGuide.Step required = steps.get(1);
        assertThat(required.action()).contains("Always send").contains("region");

        // the parameter name is restored from the spec's name index (envelopes omit it)
        MigrationGuide.Step param = steps.get(2);
        assertThat(param.action()).contains("'region' parameter").contains("Always send");
    }

    @Test
    @DisplayName("an unknown code degrades to a review step quoting the finding detail — nothing invented")
    void unknownCodesBecomeReviewSteps() {
        List<MigrationGuide.Step> steps = MigrationGuide.build(
                List.of(finding(9, "TOTALLY.NEW_CODE", "info", "GET",
                        "GET /things :: response:200:application/json", null,
                        "something changed")), Map.of(), Map.of());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).action()).startsWith("Review this change:")
                .contains("something changed");
    }

    @Test
    @DisplayName("no findings -> no steps (the caller renders the no-migration note)")
    void emptyFindingsProduceNoSteps() {
        assertThat(MigrationGuide.build(List.of(), Map.of(), Map.of())).isEmpty();
    }
}
