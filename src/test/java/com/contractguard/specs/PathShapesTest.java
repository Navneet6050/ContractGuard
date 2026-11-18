package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathShapesTest {

    @Test
    @DisplayName("placeholder names are ignored; positions are kept")
    void placeholderNamesIgnored() {
        assertThat(PathShapes.normalize("/quotes/{id}")).isEqualTo("/quotes/{0}");
        assertThat(PathShapes.normalize("/quotes/{quoteId}")).isEqualTo("/quotes/{0}");
    }

    @Test
    @DisplayName("different shapes never collide")
    void differentShapesDiffer() {
        assertThat(PathShapes.normalize("/policies/{policyId}"))
                .isNotEqualTo(PathShapes.normalize("/policies/{policyId}/endorsements"));
        assertThat(PathShapes.normalize("/policies/{a}/endorsements/{b}"))
                .isEqualTo(PathShapes.normalize("/policies/{x}/endorsements/{y}"));
    }

    @Test
    @DisplayName("literal segments are case- and slash-normalized")
    void literalsPreserved() {
        assertThat(PathShapes.normalize("quotes/{id}")).isEqualTo("/quotes/{0}");
        assertThat(PathShapes.normalize("/a//b/{c}/")).isEqualTo("/a/b/{0}");
        assertThat(PathShapes.normalize("/")).isEqualTo("/");
        assertThat(PathShapes.normalize(null)).isEqualTo("/");
    }

    @Test
    @DisplayName("curly braces inside literals are not placeholders")
    void onlyWholeSegmentPlaceholders() {
        assertThat(PathShapes.normalize("/files/{name}.txt")).isEqualTo("/files/{name}.txt");
        assertThat(PathShapes.normalize("/files/{name}")).isEqualTo("/files/{0}");
    }
}
