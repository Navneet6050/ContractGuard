package com.contractguard.consumers;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.specs.Fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer YAML parsing against the REAL kit fixture files (read-only) plus focused
 * error cases. Identity (consumer name, provider slug) must be preserved verbatim;
 * the only normalization allowed is method casing and path-shape normalization.
 */
class ConsumerParserTest {

    private final ConsumerParser parser = new ConsumerParser();

    @Test
    @DisplayName("mobile-app: identity, four bindings, sends/reads preserved verbatim")
    void parsesMobileApp() {
        ConsumerDeclaration c = parser.parse(Fixtures.consumer("mobile-app.yaml"));
        assertThat(c.name()).isEqualTo("mobile-app");
        assertThat(c.team()).isEqualTo("Mobile");
        assertThat(c.operations()).hasSize(4);

        DeclaredOperation postQuotes = c.operations().get(0);
        assertThat(postQuotes.provider()).isEqualTo("quote-api");
        assertThat(postQuotes.method()).isEqualTo("POST");
        assertThat(postQuotes.rawPath()).isEqualTo("/quotes");
        assertThat(postQuotes.sends()).containsExactly("vehicle.make", "vehicle.cc", "rider.age");
        assertThat(postQuotes.reads()).containsExactly("quoteId", "status", "premium.total",
                "premium.breakdown[].label", "premium.breakdown[].amount");

        // GET binding: the path template uses {id} while the spec uses {quoteId} — the
        // shape must normalize so they join; 'id' stays in sends (parameter usage)
        DeclaredOperation getQuote = c.operations().get(1);
        assertThat(getQuote.rawPath()).isEqualTo("/quotes/{id}");
        assertThat(getQuote.pathShape()).isEqualTo("/quotes/{0}");
        assertThat(getQuote.sends()).containsExactly("id");
        assertThat(getQuote.reads()).containsExactly("quoteId", "status", "premium.total");
    }

    @Test
    @DisplayName("partner-portal / payments-svc / notifications-svc: all parse with identity intact")
    void parsesRemainingKits() {
        ConsumerDeclaration partner = parser.parse(Fixtures.consumer("partner-portal.yaml"));
        assertThat(partner.name()).isEqualTo("partner-portal");
        assertThat(partner.team()).isEqualTo("Partner Integrations");
        assertThat(partner.forProvider("policy-api")).hasSize(1);
        assertThat(partner.forProvider("policy-api").get(0).rawPath())
                .isEqualTo("/policies/{policyId}/endorsements");
        assertThat(partner.forProvider("policy-api").get(0).sends())
                .containsExactly("policyId", "type", "details");

        ConsumerDeclaration payments = parser.parse(Fixtures.consumer("payments-svc.yaml"));
        assertThat(payments.name()).isEqualTo("payments-svc");
        assertThat(payments.forProvider("policy-api").get(0).reads())
                .contains("legacyCode", "premiumDue.amount", "premiumDue.dueDate");

        ConsumerDeclaration notifications = parser.parse(Fixtures.consumer("notifications-svc.yaml"));
        assertThat(notifications.name()).isEqualTo("notifications-svc");
        assertThat(notifications.operations()).hasSize(3);
        assertThat(notifications.forProvider("claims-api").get(0).reads())
                .containsExactly("claimId", "status");
    }

    @Test
    @DisplayName("shape normalization: equivalent templates join, different shapes never collide")
    void pathShapes() {
        ConsumerDeclaration c = parser.parse(Fixtures.consumer("mobile-app.yaml"));
        // /claims/{claimId} → /claims/{0}; must NOT equal a hypothetical /other/{claimId}
        assertThat(c.forProvider("claims-api").get(1).pathShape()).isEqualTo("/claims/{0}");
        assertThat(PathShapeProbe.shapeOf("/quotes/{id}")).isEqualTo(PathShapeProbe.shapeOf("/quotes/{quoteId}"));
        assertThat(PathShapeProbe.shapeOf("/quotes/{id}"))
                .isNotEqualTo(PathShapeProbe.shapeOf("/x/quotes/{id}"));
    }

    @Test
    @DisplayName("collect-all errors: missing consumer name and bad method reported together")
    void collectsErrors() {
        String bad = """
                team: X
                uses:
                - provider: quote-api
                  method: FETCH
                  path: /quotes
                """;
        assertThatThrownBy(() -> parser.parse(bad, "bad.yaml"))
                .isInstanceOf(ConsumerParseException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("'consumer' is required")
                .hasMessageContaining("FETCH");
    }

    @Test
    @DisplayName("structural errors: non-list sends, non-mapping use entry, non-string fields")
    void structuralErrors() {
        assertThatThrownBy(() -> parser.parse("""
                consumer: c
                uses:
                - provider: p
                  method: GET
                  path: /x
                  sends: not-a-list
                """))
                .isInstanceOf(ConsumerParseException.class)
                .hasMessageContaining("sends must be a list");

        assertThatThrownBy(() -> parser.parse("""
                consumer: c
                uses: [just-a-string]
                """))
                .isInstanceOf(ConsumerParseException.class)
                .hasMessageContaining("uses[0] must be a mapping");

        assertThatThrownBy(() -> parser.parse("""
                consumer: c
                uses:
                - provider: p
                  method: GET
                  path: no-leading-slash
                """))
                .isInstanceOf(ConsumerParseException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    @DisplayName("empty usage list and missing sends/reads are valid (nothing declared)")
    void emptyDeclarations() {
        ConsumerDeclaration c = parser.parse("""
                consumer: silent-consumer
                uses: []
                """);
        assertThat(c.name()).isEqualTo("silent-consumer");
        assertThat(c.operations()).isEmpty();

        ConsumerDeclaration minimal = parser.parse("""
                consumer: minimal
                uses:
                - provider: svc
                  method: GET
                  path: /x
                """);
        assertThat(minimal.operations()).hasSize(1);
        assertThat(minimal.operations().get(0).sends()).isEmpty();
        assertThat(minimal.operations().get(0).reads()).isEmpty();
    }

    @Test
    @DisplayName("duplicate field declarations are de-duplicated preserving first-seen order")
    void dedupes() {
        ConsumerDeclaration c = parser.parse("""
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /x
                  reads: [a, b, a, b.c]
                """);
        assertThat(c.operations().get(0).reads()).containsExactly("a", "b", "b.c");
    }

    /** Visibility shim: PathShapes lives in the specs package; used here only for cross-checks. */
    private static final class PathShapeProbe {
        static String shapeOf(String path) {
            return com.contractguard.specs.PathShapes.normalize(path);
        }
    }
}
